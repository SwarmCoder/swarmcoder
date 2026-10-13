/*
 * Copyright 2026 Franz Schöning
 * Project: https://github.com/SwarmCoder/swarmcoder
 * Author: Franz Schöning - Principal Enterprise Architect (https://www.franzschoning.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.swarmcoder.runtime;

import ai.koog.prompt.message.Message;
import ai.koog.prompt.message.MessagePart;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a session has itself replaced is not sent again whole (section 98).
 *
 * <p>Two things, each decided by which tool was called with which arguments and by nothing in
 * the text of a call or a result:
 *
 * <ul>
 *   <li><b>An earlier run.</b> A tool marked {@link AgentRuntime.RunAgain} (a build, a test run,
 *       a compile check) was called again later with the same arguments and answered. The
 *       earlier result is what the build said before the code changed.
 *   <li><b>An earlier draft.</b> A tool with a {@link AgentRuntime.Draft} parameter was called
 *       again later with the same other arguments (the same path, the same member; none at all
 *       for a tool that takes only the draft). The earlier draft, and the result it got, are of
 *       a text the session has since written again.
 * </ul>
 *
 * <p>Each is cut to one short note that says what it was, how its result began (a build's
 * first line is its exit code) and that a later call replaced it. The latest of each stays
 * whole. This is done before the next request, wherever in the conversation the earlier one
 * is: unlike {@link HistoryTrim#tidy} it does not wait for a mark, because what it cuts is
 * known to be out of date rather than merely old.
 *
 * <p>Nothing before a session's first call is ever touched, so the opening that the candidates
 * of one task share stays the same text for all of them. What a cut does cost is the server's
 * cached reading of the conversation from the cut message on, once.
 */
final class Superseded {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A result or draft no longer than this is left whole: its note would be no shorter. */
    static final int NOT_WORTH_IT = 400;

    private static final int HEAD_CHARS = 200;

    private static final int MAX_ARGS_IN_NOTE = 160;

    private Superseded() {
    }

    /**
     * A call of the turn that has just been run: its result is about to be sent and is not in
     * the conversation yet.
     */
    record Now(String id, String tool, String args, String output) {
    }

    /**
     * What one pass did.
     *
     * @param messages the conversation to use from now on; the same list when nothing was cut
     * @param results  how many results were cut to a note
     * @param drafts   how many draft arguments were cut to a note
     */
    record Result(List<Message> messages, int results, int drafts, int tokensBefore,
                  int tokensAfter) {
        boolean changed() {
            return results + drafts > 0;
        }
    }

    private static final class Seen {
        final String id;
        final String tool;
        final String args;
        String output;

        Seen(String id, String tool, String args) {
            this.id = id;
            this.tool = tool;
            this.args = args == null ? "" : args;
        }
    }

    static Result shorten(List<Message> messages, List<AgentRuntime.ToolBinding> tools,
                          List<Now> now) {
        Set<String> runAgain = new HashSet<>();
        Map<String, String> draftOf = new HashMap<>();
        declared(tools, runAgain, draftOf);
        if ((runAgain.isEmpty() && draftOf.isEmpty()) || messages == null || messages.isEmpty()) {
            return unchanged(messages);
        }

        List<Seen> calls = new ArrayList<>();
        Map<String, Seen> byId = new HashMap<>();
        for (Message message : messages) {
            for (MessagePart part : message.getParts()) {
                if (part instanceof MessagePart.Tool.Call call && call.getId() != null
                        && !byId.containsKey(call.getId())) {
                    Seen seen = new Seen(call.getId(), call.getTool(), call.getArgs());
                    calls.add(seen);
                    byId.put(seen.id, seen);
                } else if (part instanceof MessagePart.Tool.Result result
                        && byId.containsKey(result.getId())) {
                    byId.get(result.getId()).output = result.getOutput();
                }
            }
        }
        if (now != null) {
            for (Now made : now) {
                if (made == null || made.id() == null) {
                    continue;
                }
                Seen seen = byId.get(made.id());
                if (seen == null) {
                    // A call the model wrote as text: the conversation holds no call part for it.
                    seen = new Seen(made.id(), made.tool(), made.args());
                    calls.add(seen);
                    byId.put(seen.id, seen);
                }
                seen.output = made.output();
            }
        }

        // An earlier run: the same tool, the same arguments, and a later answer that is one.
        Set<String> earlierRuns = new HashSet<>();
        Map<String, List<Seen>> runs = new LinkedHashMap<>();
        // An earlier draft: the same tool and the same other arguments, written again later.
        Map<String, String> earlierDrafts = new HashMap<>();
        Map<String, List<Seen>> drafts = new LinkedHashMap<>();
        Map<String, String> targetOf = new HashMap<>();
        for (Seen seen : calls) {
            if (runAgain.contains(seen.tool)) {
                runs.computeIfAbsent(seen.tool + "\n" + seen.args, key -> new ArrayList<>())
                    .add(seen);
            }
            String parameter = draftOf.get(seen.tool);
            if (parameter != null) {
                String target = target(seen.args, parameter);
                if (target != null) {
                    targetOf.put(seen.id, target);
                    drafts.computeIfAbsent(seen.tool + "\n" + target, key -> new ArrayList<>())
                        .add(seen);
                }
            }
        }
        for (List<Seen> same : runs.values()) {
            int latest = -1;
            for (int i = 0; i < same.size(); i++) {
                String output = same.get(i).output;
                // A call answered "unchanged" sent nothing new and replaces nothing (section 59).
                if (output != null && !output.startsWith(HistoryTrim.UNCHANGED_MARK)) {
                    latest = i;
                }
            }
            for (int i = 0; i < latest; i++) {
                earlierRuns.add(same.get(i).id);
            }
        }
        for (List<Seen> same : drafts.values()) {
            int latest = -1;
            for (int i = 0; i < same.size(); i++) {
                if (same.get(i).output != null) {
                    latest = i;
                }
            }
            for (int i = 0; i < latest; i++) {
                earlierDrafts.put(same.get(i).id, draftOf.get(same.get(i).tool));
            }
        }
        if (earlierRuns.isEmpty() && earlierDrafts.isEmpty()) {
            return unchanged(messages);
        }

        List<Message> out = new ArrayList<>(messages);
        int cutResults = 0;
        int cutDrafts = 0;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) instanceof Message.Assistant assistant) {
                List<MessagePart.ResponsePart> parts = new ArrayList<>();
                int here = 0;
                for (MessagePart.ResponsePart part : assistant.getParts()) {
                    if (part instanceof MessagePart.Tool.Call call
                            && earlierDrafts.containsKey(call.getId())) {
                        String cut = withoutDraft(call.getTool(), call.getArgs(),
                            earlierDrafts.get(call.getId()));
                        if (cut != null) {
                            parts.add(call.copy(call.getId(), call.getTool(), cut,
                                call.getCacheControl()));
                            here++;
                            continue;
                        }
                    }
                    parts.add(part);
                }
                if (here > 0) {
                    out.set(i, assistant.copy(parts, assistant.getMetaInfo(),
                        assistant.getFinishReason(), assistant.getRawResponse(),
                        assistant.getId()));
                    cutDrafts += here;
                }
            } else if (out.get(i) instanceof Message.User user) {
                List<MessagePart.RequestPart> parts = new ArrayList<>();
                int here = 0;
                for (MessagePart.RequestPart part : user.getParts()) {
                    if (part instanceof MessagePart.Tool.Result result && worthCutting(result)) {
                        String note = null;
                        if (earlierDrafts.containsKey(result.getId())) {
                            note = draftResultNote(result);
                        } else if (earlierRuns.contains(result.getId())) {
                            Seen seen = byId.get(result.getId());
                            note = runNote(result, seen == null ? "" : seen.args);
                        }
                        if (note != null && note.length() < result.getOutput().length()) {
                            parts.add(new MessagePart.Tool.Result(result.getId(),
                                result.getTool(), note, result.isError()));
                            here++;
                            continue;
                        }
                    }
                    parts.add(part);
                }
                if (here > 0) {
                    out.set(i, user.copy(parts, user.getMetaInfo(), user.getId()));
                    cutResults += here;
                }
            }
        }
        if (cutResults + cutDrafts == 0) {
            return unchanged(messages);
        }
        return new Result(List.copyOf(out), cutResults, cutDrafts,
            HistoryTrim.estimateTokens(messages), HistoryTrim.estimateTokens(out));
    }

    private static Result unchanged(List<Message> messages) {
        return new Result(messages, 0, 0, 0, 0);
    }

    /** Which tools are run again, and which parameter of which tool carries a draft. */
    private static void declared(List<AgentRuntime.ToolBinding> tools, Set<String> runAgain,
                                 Map<String, String> draftOf) {
        if (tools == null) {
            return;
        }
        for (AgentRuntime.ToolBinding tool : tools) {
            if (tool == null || tool.method() == null) {
                continue;
            }
            if (tool.method().isAnnotationPresent(AgentRuntime.RunAgain.class)) {
                runAgain.add(tool.name());
            }
            for (Parameter parameter : tool.method().getParameters()) {
                if (parameter.getType() == String.class
                        && parameter.isAnnotationPresent(AgentRuntime.Draft.class)) {
                    draftOf.put(tool.name(), parameter.getName());
                    break;
                }
            }
        }
    }

    private static ObjectNode object(String args) {
        try {
            JsonNode read = args == null || args.isBlank() ? null : JSON.readTree(args);
            return read != null && read.isObject() ? (ObjectNode) read : null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    /**
     * What a call's draft is a draft of: its other arguments, in one fixed order. Null when the
     * arguments cannot be read or carry no draft.
     */
    private static String target(String args, String draftParameter) {
        ObjectNode object = object(args);
        if (object == null || !object.hasNonNull(draftParameter)
                || !object.get(draftParameter).isTextual()) {
            return null;
        }
        Map<String, String> others = new TreeMap<>();
        object.fields().forEachRemaining(field -> {
            if (!field.getKey().equals(draftParameter)) {
                others.put(field.getKey(), field.getValue().toString());
            }
        });
        return others.toString();
    }

    /**
     * The arguments with the draft replaced by a note, as valid JSON with the same keys in the
     * same order. Null when the draft is too short to be worth it or was already cut.
     */
    static String withoutDraft(String tool, String args, String draftParameter) {
        ObjectNode object = object(args);
        if (object == null || !object.hasNonNull(draftParameter)
                || !object.get(draftParameter).isTextual()) {
            return null;
        }
        String draft = object.get(draftParameter).asText();
        if (draft.length() <= NOT_WORTH_IT || draft.contains(HistoryTrim.ELIDED_MARK)) {
            return null;
        }
        List<String> others = new ArrayList<>();
        object.fieldNames().forEachRemaining(name -> {
            if (!name.equals(draftParameter)) {
                others.add(name);
            }
        });
        object.put(draftParameter, HistoryTrim.ELIDED_MARK + " An earlier draft of "
            + draft.length() + " characters. A later " + tool + " call of yours"
            + (others.isEmpty() ? "" : " with the same " + String.join(", ", others))
            + " replaced it; the latest one is whole.");
        return object.toString();
    }

    private static boolean worthCutting(MessagePart.Tool.Result result) {
        String output = result.getOutput();
        return output != null && output.length() > NOT_WORTH_IT
            && !output.startsWith(HistoryTrim.ELIDED_MARK)
            && !output.startsWith(HistoryTrim.UNCHANGED_MARK);
    }

    private static String runNote(MessagePart.Tool.Result result, String args) {
        String shortArgs = args == null || args.isBlank() ? "" : " " + (args.length()
            > MAX_ARGS_IN_NOTE ? args.substring(0, MAX_ARGS_IN_NOTE) + "..." : args);
        return HistoryTrim.ELIDED_MARK + " The result of an earlier " + result.getTool()
            + shortArgs + " (" + result.getOutput().length() + " characters). You made the same "
            + "call again later, and that result replaces this one." + head(result.getOutput());
    }

    private static String draftResultNote(MessagePart.Tool.Result result) {
        return HistoryTrim.ELIDED_MARK + " The result of " + result.getTool() + " for an earlier "
            + "draft (" + result.getOutput().length() + " characters). You have since replaced "
            + "that draft, and the result for the latest one is whole."
            + head(result.getOutput());
    }

    /**
     * How a result began: its first line that says anything, which is where a verdict stands.
     * A tool that returns a string has it kept as a JSON string, its line ends escaped, so that
     * form is read first.
     */
    private static String head(String output) {
        String text = output;
        if (output.startsWith("\"")) {
            try {
                JsonNode read = JSON.readTree(output);
                if (read != null && read.isTextual()) {
                    text = read.asText();
                }
            } catch (Exception notJson) {
                text = output;
            }
        }
        for (String line : text.split("\n", -1)) {
            String said = line.strip();
            if (!said.isEmpty()) {
                return " It began: " + (said.length() > HEAD_CHARS
                    ? said.substring(0, HEAD_CHARS) + "..." : said);
            }
        }
        return "";
    }
}
