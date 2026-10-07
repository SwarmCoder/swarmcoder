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

import ai.koog.prompt.message.MessagePart;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Recovers a tool call the model wrote as ORDINARY TEXT instead of as a structured tool call.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Models served by a chat template that cannot take native tool-call history are driven with
 * {@code quirks.textualToolHistory}: the agent framework rewrites every earlier tool call and
 * tool result in the conversation into a plain-text JSON line of the shape
 *
 * <pre>{"tool_call_id":"…","tool_name":"write_file","tool_args":{…}}</pre>
 *
 * <p>That works — the tool DEFINITIONS still go natively, so the model keeps calling tools — but
 * it also hands the model several worked examples of "a tool call is a line of text like this",
 * written in its own voice. Small models imitate it. Measured on a Qwen 27B worker: turns 1 and 2
 * were real tool calls, and from turn 3 onward the model emitted that exact JSON as assistant
 * TEXT, complete with an invented {@code call_…} id. The loop saw no tool calls, counted two text
 * turns in a row and ended the session. Across one full run that was 27 of 27 workers finishing
 * with zero files written, every one of them signing off with a text-shaped tool call.
 *
 * <p>So the conversion is only half a protocol: we speak that dialect to the model but did not
 * listen for it coming back. This class is the other half. A text turn whose content is one of
 * those lines, naming a tool the session actually has, is executed as the tool call it plainly is.
 *
 * <p>Not a licence for sloppy output: only well-formed objects naming a REGISTERED tool are
 * accepted, and anything else is still an ordinary text turn that ends the session as before.
 */
public final class TextEmittedToolCalls {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** A model that repeats itself can emit a long list; bound what one turn may recover. */
    private static final int MAX_RECOVERED = 8;

    private TextEmittedToolCalls() {}

    /**
     * Tool calls hidden in an assistant text turn, in the order they appear; empty when the text
     * is ordinary prose or names no known tool.
     *
     * @param text      the assistant turn's text content
     * @param knownTools names of the tools registered for this session
     */
    public static List<MessagePart.Tool.Call> parse(String text, Collection<String> knownTools) {
        if (text == null || text.isBlank() || knownTools == null || knownTools.isEmpty()) {
            return List.of();
        }
        Set<String> known = Set.copyOf(knownTools);
        List<MessagePart.Tool.Call> calls = new ArrayList<>();
        for (String candidate : jsonObjects(text)) {
            MessagePart.Tool.Call call = toCall(candidate, known);
            if (call != null) {
                calls.add(call);
                if (calls.size() >= MAX_RECOVERED) {
                    break;
                }
            }
        }
        return List.copyOf(calls);
    }

    private static MessagePart.Tool.Call toCall(String json, Set<String> knownTools) {
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
        if (node == null || !node.isObject()) {
            return null;
        }
        // Two spellings are accepted: the framework's own textual-history shape (tool_name /
        // tool_args — the one the model is being shown and therefore imitates) and the OpenAI
        // wire shape (name / arguments), because a model that has seen both in training reaches
        // for either.
        String tool = textOf(node, "tool_name");
        JsonNode args = node.get("tool_args");
        if (tool == null) {
            tool = textOf(node, "name");
            args = node.get("arguments");
        }
        if (tool == null || !knownTools.contains(tool)) {
            return null;
        }
        String argsJson = argumentsJson(args);
        if (argsJson == null) {
            return null;
        }
        String id = textOf(node, "tool_call_id");
        return new MessagePart.Tool.Call(id == null ? null : id, tool, argsJson);
    }

    /** The arguments as a JSON OBJECT string, unwrapping the "arguments is a JSON string" spelling. */
    private static String argumentsJson(JsonNode args) {
        if (args == null) {
            return null;
        }
        if (args.isObject()) {
            return args.toString();
        }
        if (args.isTextual()) {
            try {
                JsonNode inner = MAPPER.readTree(args.asText());
                return inner != null && inner.isObject() ? inner.toString() : null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /**
     * Every balanced top-level {@code { … }} region in the text, in order.
     *
     * <p>Scanned by hand rather than handed to a JSON parser because the turn is not JSON: it is
     * prose, or a fenced code block, or two objects written back to back with nothing between
     * them (which is exactly what a model imitating two parallel tool calls produces). String
     * literals and their escapes are tracked so a brace inside a quoted shell command — and file
     * content is full of them — cannot end the region early.
     */
    private static List<String> jsonObjects(String text) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        objects.add(text.substring(start, i + 1));
                        start = -1;
                    }
                }
            }
        }
        return objects;
    }
}
