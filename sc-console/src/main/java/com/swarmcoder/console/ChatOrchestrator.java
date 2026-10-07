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
package com.swarmcoder.console;

import com.swarmcoder.console.api.ChatStreamDto;
import com.swarmcoder.domain.ChatMessage;
import com.swarmcoder.domain.ChatSession;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The coder side of a chat (CONSOLE_DESIGN_V2.md §5.2): freeform turns stream from the
 * project's chat model; {@code /run}-style commands start a run bound to the chat, whose
 * lifecycle is then narrated into the transcript (state transitions, parked decisions,
 * the APPROVAL gate). Everything lands in the store first and is pushed to the browser
 * on {@code chat-events} / {@code chat-stream}.
 */
final class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);
    private static final ChatOrchestrator INSTANCE = new ChatOrchestrator();
    private static final int CONTEXT_MESSAGES = 20;
    private static final long NARRATE_POLL_MS = 1_500;
    private static final long NARRATE_CEILING_MS = 60 * 60_000;

    private final Map<UUID, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final Set<UUID> narrating = ConcurrentHashMap.newKeySet();
    /** Per-chat serial turn queue: a message sent while the coder is busy waits its turn. */
    private final Map<UUID, BlockingQueue<String>> queues = new ConcurrentHashMap<>();
    private final Set<UUID> processing = ConcurrentHashMap.newKeySet();

    static ChatOrchestrator get() {
        return INSTANCE;
    }

    private ChatOrchestrator() {}

    /**
     * Accepts a user message: the USER bubble is persisted immediately (so it appears even
     * while the coder is busy), then the turn is QUEUED and processed serially per chat — a
     * message sent mid-reply waits its turn instead of starting a concurrent stream.
     */
    String send(UUID chatId, String text) {
        ConsoleContext context = ConsoleContext.get();
        ChatSession chat = context.store().root().chats().get(chatId);
        if (chat == null) {
            return "error: unknown chat " + chatId;
        }
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return "error: empty message";
        }
        append(chatId, "USER", "TEXT", trimmed, null, null, 0);
        maybeAutoTitle(chat, trimmed);
        boolean busy = processing.contains(chatId);
        queues.computeIfAbsent(chatId, k -> new LinkedBlockingQueue<>()).add(trimmed);
        if (busy) {
            append(chatId, "SYSTEM", "TOOL", "_queued — the coder will respond after the current "
                + "reply_", null, null, 0);
        } else {
            startTurnPump(context, chatId);
        }
        return "";
    }

    /**
     * The slash commands this build understands, served to the composer's autocomplete.
     *
     * <p>Kept next to {@link #commandKind} and {@link #processTurn}, which are what actually
     * dispatch them: a list maintained in the client would drift the moment a command is added
     * here, and the UI whose job is to reveal the commands would be the last thing to know.
     */
    static List<String> commands() {
        // /docs and /analyze are gone. They started a "run" that renamed its own state and then
        // reported DELIVERED, having designed nothing, planned nothing, written no test and
        // dispatched no worker — and neither is a build: nothing they produce can be stated as a
        // check a test proves, which is what everything downstream here works from. A menu entry
        // that starts nothing is worse than a missing feature, because it answers.
        return List.of(
            "/run	Start a run from a goal, or from the analyst's proposed goal",
            "/bugfix	Start a bugfix run: reproduce the fault with a failing test, then fix it",
            "/refactor	Start a refactor run: change the shape, keep the behaviour");
    }

    /**
     * Re-answers the last user message, discarding the reply that was there.
     *
     * <p>The old answer is REMOVED rather than appended to: the transcript is also the model's
     * context, so keeping both would leave two answers to one question in it and condition the
     * next turn on a reply the operator had just rejected.
     */
    String regenerate(UUID chatId) {
        ConsoleContext context = ConsoleContext.get();
        ChatSession chat = context.store().root().chats().get(chatId);
        if (chat == null) {
            return "error: unknown chat " + chatId;
        }
        if (processing.contains(chatId)) {
            return "error: the coder is still replying — stop it first";
        }
        List<ChatMessage> transcript = context.store().chatTranscript(chatId);
        ChatMessage lastCoder = null;
        String lastUser = null;
        for (ChatMessage message : transcript) {
            if ("CODER".equals(message.role())) {
                lastCoder = message;
            } else if ("USER".equals(message.role())) {
                lastUser = message.markdown();
                lastCoder = null;   // a newer user message means the reply below it is the one
            }
        }
        if (lastUser == null) {
            return "error: nothing to regenerate";
        }
        if (lastCoder != null) {
            context.store().deleteChatMessage(lastCoder.id());
            context.push("chat-list", chat);
        }
        queues.computeIfAbsent(chatId, k -> new LinkedBlockingQueue<>()).add(lastUser);
        startTurnPump(context, chatId);
        return "";
    }

    /** Drains the chat's queue serially; each turn blocks until its reply/run kickoff completes. */
    private void startTurnPump(ConsoleContext context, UUID chatId) {
        if (!processing.add(chatId)) {
            return;
        }
        Thread pump = new Thread(() -> {
            try {
                var queue = queues.get(chatId);
                String message;
                while (queue != null && (message = queue.poll()) != null) {
                    processTurn(context, chatId, message);
                }
            } finally {
                processing.remove(chatId);
                // A message enqueued in the race after the loop drained restarts the pump.
                var queue = queues.get(chatId);
                if (queue != null && !queue.isEmpty()) {
                    startTurnPump(context, chatId);
                }
            }
        }, "chat-pump-" + shortId(chatId));
        pump.setDaemon(true);
        pump.start();
    }

    /** One turn: a slash command starts a run (returns immediately), else a streamed reply (blocks). */
    private void processTurn(ConsoleContext context, UUID chatId, String trimmed) {
        ChatSession chat = context.store().root().chats().get(chatId);
        if (chat == null) {
            return;
        }
        // No authoring MODES here any more. Backlog planning is the backlog's "Plan stories"
        // wizard and requirements are the Requirements panel's "Analyse documents" wizard — both
        // guided flows with visible inputs, progress and a review step, which a sticky chat mode
        // could never show (docs/GUIDED_FLOWS_DESIGN.md §1). A chat turn is a reply or a run.
        String kind = commandKind(trimmed);
        if (kind != null) {
            String goal = trimmed.contains(" ")
                ? trimmed.substring(trimmed.indexOf(' ') + 1).strip() : "";
            if (goal.isEmpty()) {
                goal = lastProposedGoal(context, chatId);
            }
            if (goal.isEmpty()) {
                append(chatId, "SYSTEM", "ERROR",
                    "Give the run a goal: `/" + kind.toLowerCase() + " <what to build>` — or "
                    + "describe the work first and I will propose one.", null, null, 0);
                return;
            }
            startRun(context, chat, kind, goal);
        } else {
            streamReplyBlocking(context, chat);
        }
    }

    /** The goal from the newest coder message carrying a PROPOSED RUN GOAL line, else "". */
    private static String lastProposedGoal(ConsoleContext context, UUID chatId) {
        List<ChatMessage> transcript = context.store().chatTranscript(chatId);
        for (int i = transcript.size() - 1; i >= 0; i--) {
            ChatMessage message = transcript.get(i);
            if (!"CODER".equals(message.role()) || message.markdown() == null) {
                continue;
            }
            int marker = message.markdown().indexOf("PROPOSED RUN GOAL:");
            if (marker >= 0) {
                String goal = message.markdown().substring(marker + "PROPOSED RUN GOAL:".length());
                int paragraphEnd = goal.indexOf("\n\n");
                if (paragraphEnd > 0) {
                    goal = goal.substring(0, paragraphEnd);
                }
                return goal.replace("**", "").replace("\n", " ").strip();
            }
        }
        return "";
    }

    void stop(UUID chatId) {
        AtomicBoolean flag = cancelFlags.get(chatId);
        if (flag != null) {
            flag.set(true);
        }
    }

    /**
     * Attaches a pasted image as a USER message (kind IMAGE, body = the data: URI). It is not
     * sent to the model (the chat model may not be vision-capable) — it is operator context
     * the accompanying text message discusses. Capped to keep the store lean.
     */
    String sendImage(UUID chatId, String dataUri) {
        ConsoleContext context = ConsoleContext.get();
        if (context.store().root().chats().get(chatId) == null) {
            return "error: unknown chat " + chatId;
        }
        if (dataUri == null || !dataUri.startsWith("data:image/")) {
            return "error: not an image";
        }
        if (dataUri.length() > 4_000_000) {
            return "error: image too large (max ~3 MB)";
        }
        append(chatId, "USER", "IMAGE", dataUri, null, null, 0);
        return "";
    }

    // --- run binding + narration -----------------------------------------------------------------

    private void startRun(ConsoleContext context, ChatSession chat, String kind, String goal) {
        try {
            // The work item is created BEFORE the run, and the run is started already bound to it.
            // Doing it the other way round loses the binding every time — see AdHocStory.start.
            AdHocStory.Started started =
                AdHocStory.start(context, chat.projectId(), kind, goal);
            UUID runId = started != null ? started.runId() : context.startRun(goal, kind);
            chat.setBoundRunId(runId);
            context.store().saveChat(chat);
            append(chat.id(), "SYSTEM", "RUN_EVENT",
                "**" + kind + " run started** `" + shortId(runId) + "`\n\n> " + goal, runId, null, 0);
            if (narrating.add(runId)) {
                Thread narrator = new Thread(() -> narrate(context, chat.id(), runId),
                    "chat-narrator-" + shortId(runId));
                narrator.setDaemon(true);
                narrator.start();
            }
        } catch (Exception e) {
            log.warn("Chat {}: intake failed: {}", chat.id(), e.getMessage());
            append(chat.id(), "SYSTEM", "ERROR", "Run intake failed: " + e.getMessage(), null, null, 0);
        }
    }

    /**
     * Narrates a run into its chat by watching the store — state transitions become
     * RUN_EVENT cards, and a question a build stopped to ask becomes a DECISION card that says so
     * and points at the story it belongs to. Polling the
     * store is the C2-2 mechanism; the run-graph delta push (C2-3) replaces it.
     */
    private void narrate(ConsoleContext context, UUID chatId, UUID runId) {
        try {
            RunState lastState = null;
            Set<UUID> seenDecisions = new HashSet<>();
            long deadline = System.currentTimeMillis() + NARRATE_CEILING_MS;
            while (System.currentTimeMillis() < deadline) {
                Run run = context.store().root().runs.get(runId);
                if (run != null && run.state() != lastState) {
                    lastState = run.state();
                    append(chatId, "SYSTEM", "RUN_EVENT", stateNarration(run), runId, null, 0);
                    if (lastState == RunState.DELIVERED || lastState == RunState.ABORTED
                            || lastState == RunState.ABANDONED) {
                        return;
                    }
                }
                for (Decision decision : context.store().root().decisions.values()) {
                    if (decision.state() == DecisionState.PENDING
                            && runId.equals(decision.runId())
                            && seenDecisions.add(decision.id())) {
                        // The brief alone. It used to be prefixed with "**Decision needed**
                        // (BLOCKED_TASK)" - the Java constant, printed into a line a person
                        // reads, and the very string the chat card then searched for the
                        // word APPROVAL to decide which buttons to draw. The card now says
                        // in words what this is, and the question is answered on the story
                        // card it belongs to, not here (UX v3 2.3, 9 step 4).
                        append(chatId, "SYSTEM", "DECISION", decision.briefMarkdown(),
                            runId, decision.id(), 0);
                    }
                }
                Thread.sleep(NARRATE_POLL_MS);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Chat narration for run {} died: {}", runId, e.getMessage());
        } finally {
            narrating.remove(runId);
        }
    }

    private static String stateNarration(Run run) {
        return switch (run.state()) {
            case DESIGN -> "Architect is drafting the design…";
            case DESIGN_REVIEW -> "Design under review…";
            case PLAN -> "Planning the task graph…";
            case TEST_AUTHORING -> "Authoring acceptance tests (must be red before dispatch)…";
            case EXECUTING -> "**Swarm dispatched** — workers are implementing candidates.";
            case FINAL_INTEGRATION -> "Integrating winning candidates…";
            // No APPROVAL narration: runs no longer park at a gate of their own (UX v3 §2.3). The
            // remaining question — "is this what I asked for?" — is the story's, and it is answered on
            // the story's card, so narrating a second gate here would point at a button that is gone.
            case DELIVERED -> "**Build finished** — the story is waiting for you to judge its delivery.";
            case ABORTED -> "**Run aborted.**";
            default -> "State → " + run.state();
        };
    }

    // --- freeform streaming ------------------------------------------------------------------

    private static final int MAX_TOOL_ROUNDS = 8;

    /** Streams the coder's reply SYNCHRONOUSLY (called on the per-chat pump thread). */
    private void streamReplyBlocking(ConsoleContext context, ChatSession chat) {
        if (context.chatModel() == null) {
            append(chat.id(), "SYSTEM", "ERROR",
                "No chat model configured — set roles.chat (or roles.utility) in config.yaml.",
                null, null, 0);
            return;
        }
        UUID chatId = chat.id();
        AtomicBoolean cancel = new AtomicBoolean(false);
        cancelFlags.put(chatId, cancel);
        try {
            // Research loop (author requirement): the analyst may issue TOOL lines —
            // executed against the confined curator — before its final answer.
            List<Map<String, String>> messages = conversation(context, chat);
            String modelOverride = chat.modelOverride();
            for (int round = 0; round <= MAX_TOOL_ROUNDS && !cancel.get(); round++) {
                String reply = streamOnce(context, chatId, messages, cancel, modelOverride).strip();
                String toolLine = toolLine(reply);
                if (toolLine == null || context.chatTools() == null || round == MAX_TOOL_ROUNDS) {
                    if (cancel.get() && !reply.isEmpty()) {
                        reply = reply + "\n\n*(stopped)*";
                    }
                    if (!reply.isEmpty()) {
                        append(chatId, "CODER", "TEXT", reply, null, null, reply.length() / 4);
                    }
                    return;
                }
                String result = executeTool(context, toolLine);
                append(chatId, "SYSTEM", "TOOL", "`" + toolLine + "`", null, null, 0);
                messages.add(Map.of("role", "assistant", "content", reply));
                messages.add(Map.of("role", "user", "content",
                    "TOOL RESULT for `" + toolLine + "`:\n" + result
                    + "\n\nContinue: request another TOOL line if needed, otherwise give "
                    + "your final answer (never mention the tool protocol to the operator)."));
            }
        } catch (Exception e) {
            log.warn("Chat {} stream failed: {}", chatId, e.getMessage());
            append(chatId, "SYSTEM", "ERROR", "Coder reply failed: " + e.getMessage(), null, null, 0);
        } finally {
            pushStream(context, chatId, "", true);
            cancelFlags.remove(chatId, cancel);
        }
    }

    /** One model call, streamed to the browser; returns the full text. */
    private static String streamOnce(ConsoleContext context, UUID chatId,
                                     List<Map<String, String>> messages, AtomicBoolean cancel,
                                     String modelOverride) throws Exception {
        StringBuilder accumulated = new StringBuilder();
        long lastPush = 0;
        try (Stream<String> stream = context.chatModel().stream(messages, modelOverride)) {
            for (Iterator<String> it = stream.iterator(); it.hasNext(); ) {
                if (cancel.get()) {
                    break;
                }
                accumulated.append(it.next());
                long now = System.currentTimeMillis();
                if (now - lastPush > 80) {
                    lastPush = now;
                    pushStream(context, chatId, accumulated.toString(), false);
                }
            }
        }
        return accumulated.toString();
    }

    /** The TOOL command when the reply's first non-blank line is one, else null. */
    static String toolLine(String reply) {
        for (String line : reply.split("\n")) {
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                continue;
            }
            return stripped.startsWith("TOOL ") ? stripped : null;
        }
        return null;
    }

    private static String executeTool(ConsoleContext context, String toolLine) {
        try {
            String[] parts = toolLine.split("\\s+", 3);
            String tool = parts.length > 1 ? parts[1] : "";
            String argument = parts.length > 2 ? parts[2].strip() : "";
            return switch (tool) {
                case "read_file" -> context.chatTools().readFile(argument);
                case "list_folder" -> context.chatTools().listFolder(argument);
                case "search_code" -> context.chatTools().searchCode(argument);
                case "lookup_docs" -> context.chatTools().lookupDocs(argument);
                case "get_source" -> renderSource(context.store(),
                    ConsoleContext.get().currentProjectId(), argument);
                default -> "error: unknown tool '" + tool
                    + "' — use read_file | list_folder | search_code | lookup_docs | get_source";
            };
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * How many rounds of questions the analyst may ask before it must commit to a goal.
     *
     * <p>The design's acceptance criterion is "an ambiguous goal triggers ≤10 questions then a
     * plan". Counted in TURNS rather than question marks because a turn is the unit the operator
     * experiences; at 2-4 questions per turn this lands under ten.
     */
    private static final int INTERVIEW_QUESTION_TURNS = 3;

    /**
     * Coder turns in this chat that asked something without yet proposing a goal.
     *
     * <p>Resets naturally: once a PROPOSED RUN GOAL is emitted the count stops mattering, and a
     * later change of subject starts a fresh interview because only turns after the most recent
     * proposal are counted.
     */
    private static int questionTurns(ConsoleContext context, ChatSession chat) {
        List<ChatMessage> transcript = context.store().chatTranscript(chat.id());
        int asked = 0;
        for (ChatMessage message : transcript) {
            if (!"CODER".equals(message.role()) || message.markdown() == null) {
                continue;
            }
            if (message.markdown().contains("PROPOSED RUN GOAL:")) {
                asked = 0;   // the interview concluded; anything after it is a new one
            } else if (message.markdown().contains("?")) {
                asked++;
            }
        }
        return asked;
    }

    /**
     * Appends the recent transcript, expanding @mentions on the CURRENT turn.
     *
     * <p>One builder rather than a copy of this loop per caller. When the authoring modes inlined
     * their own, @mentions were expanded in freeform chat and silently dropped in the other — the
     * autocomplete appeared to work and the model received a bare path.
     */
    private static void appendTranscript(ConsoleContext context, ChatSession chat,
                                         List<Map<String, String>> messages) {
        List<ChatMessage> transcript = context.store().chatTranscript(chat.id());
        int from = Math.max(0, transcript.size() - CONTEXT_MESSAGES);
        List<ChatMessage> window = transcript.subList(from, transcript.size());
        int lastUserIdx = -1;
        for (int i = 0; i < window.size(); i++) {
            if ("USER".equals(window.get(i).role())) {
                lastUserIdx = i;
            }
        }
        for (int i = 0; i < window.size(); i++) {
            ChatMessage message = window.get(i);
            if ("USER".equals(message.role())) {
                messages.add(Map.of("role", "user", "content", i == lastUserIdx
                    ? withMentions(context, nullSafe(message.markdown()))
                    : nullSafe(message.markdown())));
            } else if ("CODER".equals(message.role())) {
                messages.add(Map.of("role", "assistant", "content", nullSafe(message.markdown())));
            }
        }
    }

    /** How much uploaded source text one {@code get_source} call returns. */
    private static final int SOURCE_PAGE_CHARS = 12_000;

    /**
     * Lists the project's uploaded documents, or returns a page of one's extracted text. Vision
     * extractions are labelled so the agent knows it is reading a MODEL'S READING of an image and
     * can flag anything it treats as a requirement accordingly.
     */
    private static String renderSource(com.swarmcoder.store.ArtifactStore store, UUID projectId,
                                       String argument) {
        List<com.swarmcoder.domain.SourceDocument> documents = store.listSourceDocuments(projectId);
        if (documents.isEmpty()) {
            return "(no documents have been uploaded to this project — the operator can drop one "
                + "into the Requirements tab, or paste the text into the chat)";
        }
        String[] args = argument == null ? new String[0] : argument.trim().split("\\s+");
        if (args.length == 0 || args[0].isBlank()) {
            StringBuilder sb = new StringBuilder("Uploaded documents:\n");
            for (int i = 0; i < documents.size(); i++) {
                var d = documents.get(i);
                sb.append("  ").append(i + 1).append(". ").append(d.filename())
                    .append(" (").append(d.extractedText() == null ? 0 : d.extractedText().length())
                    .append(" chars, read by ").append(d.extractedBy()).append(")\n");
            }
            sb.append("Use: TOOL get_source <n> [page]");
            return sb.toString();
        }
        int index;
        try {
            index = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            return "error: get_source <n> [page] — n is the number from the list";
        }
        if (index < 1 || index > documents.size()) {
            return "error: there are " + documents.size() + " documents";
        }
        var document = documents.get(index - 1);
        String text = document.extractedText() == null ? "" : document.extractedText();
        int page = 1;
        if (args.length > 1) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ignored) {
                // an unparseable page is simply the first one
            }
        }
        int pages = Math.max(1, (text.length() + SOURCE_PAGE_CHARS - 1) / SOURCE_PAGE_CHARS);
        if (page > pages) {
            return "error: '" + document.filename() + "' has " + pages + " page(s)";
        }
        int start = (page - 1) * SOURCE_PAGE_CHARS;
        String slice = text.substring(start, Math.min(text.length(), start + SOURCE_PAGE_CHARS));
        String provenance = document.extractedBy() != null && document.extractedBy().startsWith("vision")
            ? "\n(NOTE: this text is a vision model's READING of an image, not the document itself — "
                + "treat anything ambiguous as a question for the operator rather than a fact.)"
            : "";
        return document.filename() + " — page " + page + " of " + pages + provenance + "\n\n" + slice;
    }

    /** System prompt + the last N text turns, oldest first, in OpenAI message shape. */
    private static List<Map<String, String>> conversation(ConsoleContext context, ChatSession chat) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
            "You are SwarmCoder's requirements analyst — the conversational front door of a "
            + "swarm-based coding agent working on the project '"
            + nullSafe(chatTitleProject(context, chat)) + "'.\n"
            + context.chatContext() + "\n"
            + "YOUR JOB is to scope work, never to implement it:\n"
            + "1. When the operator describes something to build, interview them briefly — "
            + "ask ONLY the clarifying questions whose answers would change the design "
            + "(2-4 per turn, at most ~8 total). Surface hidden requirements, constraints, "
            + "and risks.\n"
            + "2. When the scope is clear (or the operator says to proceed), reply with a "
            + "concise requirements summary followed by ONE final line in exactly this "
            + "format:\n"
            + "PROPOSED RUN GOAL: <a self-contained 1-3 sentence goal a coding agent can "
            + "execute against this repository>\n"
            + "then tell the operator to type /run to start (bare /run picks up your "
            + "proposed goal; /bugfix and /refactor work too).\n"
            + "HARD RULES: NEVER write implementation code, class skeletons, or file "
            + "contents in chat — the swarm's architect designs and verified workers "
            + "implement; chat code is unverified and forbidden. NEVER invent APIs: consult "
            + "the FRAMEWORK REFERENCE above and your research tools, and treat anything "
            + "not found as nonexistent. Short answers to short questions; markdown; no "
            + "flattery."
            + (context.chatTools() == null ? "" :
            "\nRESEARCH TOOLS: you can read the project and its reference frameworks "
            + "YOURSELF — never claim you cannot access them, and prefer researching over "
            + "asking the operator about code. To use a tool, reply with EXACTLY one line "
            + "and nothing else:\n"
            + "TOOL read_file <root>/<relative-path>\n"
            + "TOOL list_folder <root>/<path>   (empty path lists the roots)\n"
            + "TOOL search_code <query>\n"
            + "TOOL lookup_docs <library or API question>   (versioned library docs)\n"
            + "You get the result back and may chain up to 8 tools before your final "
            + "answer. Never show TOOL lines or protocol details in your final answer.")));
        appendTranscript(context, chat, messages);
        // The interview budget, enforced rather than merely requested. The persona asks for "at
        // most ~8" questions, which a model is free to ignore forever; once the budget is spent
        // this DIRECTS it to converge and to state the assumptions it is converging on, so an
        // unclear goal becomes an explicit guess the operator can correct rather than an endless
        // interrogation.
        int asked = questionTurns(context, chat);
        if (asked >= INTERVIEW_QUESTION_TURNS) {
            messages.add(Map.of("role", "user", "content",
                "[system] You have asked " + asked + " rounds of questions, which is the limit. "
                + "Do NOT ask anything further. Reply now with the requirements summary and the "
                + "PROPOSED RUN GOAL line, stating explicitly any assumption you had to make "
                + "because it was not answered."));
        }
        return messages;
    }

    /** File addresses referenced with {@code @<root>/<path>} in a message (deduped, in order). */
    private static final Pattern MENTION =
        Pattern.compile("@([A-Za-z0-9_.\\-]+/[A-Za-z0-9_./\\-]+)");

    static List<String> extractMentions(String text) {
        List<String> mentions = new ArrayList<>();
        if (text != null) {
            Matcher matcher = MENTION.matcher(text);
            while (matcher.find()) {
                String address = matcher.group(1);
                if (!mentions.contains(address)) {
                    mentions.add(address);
                }
            }
        }
        return mentions;
    }

    /**
     * Expands @mentions in a user turn by appending each referenced file's contents (read via
     * the confined chat tools) so the model sees the actual code, not just a path. No-op when no
     * mentions or no tools are wired.
     */
    static String withMentions(ConsoleContext context, String userText) {
        if (context.chatTools() == null || userText == null) {
            return userText;
        }
        List<String> mentions = extractMentions(userText);
        if (mentions.isEmpty()) {
            return userText;
        }
        StringBuilder sb = new StringBuilder(userText);
        sb.append("\n\n--- Referenced files (from @mentions) ---");
        for (String address : mentions) {
            String content;
            try {
                content = context.chatTools().readFile(address);
            } catch (Exception e) {
                content = "error: " + e.getMessage();
            }
            sb.append("\n\n@").append(address).append(":\n```\n").append(content).append("\n```");
        }
        return sb.toString();
    }

    private static String chatTitleProject(ConsoleContext context, ChatSession chat) {
        var project = context.store().getProject(chat.projectId());
        return project == null ? "default" : project.name();
    }

    // --- shared plumbing ------------------------------------------------------------------------

    /** Persists a message (store assigns seq) and pushes it to the browser. */
    private static void append(UUID chatId, String role, String kind, String markdown,
                               UUID runId, UUID decisionId, long tokens) {
        ConsoleContext context = ConsoleContext.get();
        ChatMessage message = context.store().appendChatMessage(new ChatMessage(
            UUID.randomUUID(), chatId, 0, role, kind, markdown, runId, decisionId,
            Instant.now(), tokens));
        context.push("chat-events", message);
    }

    private static void pushStream(ConsoleContext context, UUID chatId, String text, boolean done) {
        ChatStreamDto dto = new ChatStreamDto();
        dto.setChatId(chatId.toString());
        dto.setText(text);
        dto.setDone(done);
        context.push("chat-stream", dto);
    }

    private void maybeAutoTitle(ChatSession chat, String firstText) {
        if (chat.title() == null || chat.title().isBlank() || "New chat".equals(chat.title())) {
            String title = firstText.length() > 42 ? firstText.substring(0, 42) + "…" : firstText;
            chat.setTitle(title);
            ConsoleContext context = ConsoleContext.get();
            context.store().saveChat(chat);
            context.push("chat-list", chat); // the sidebar + tab pick up the new title
        }
    }

    /**
     * GREENFIELD | BUGFIX | REFACTOR from a leading slash command, else null.
     *
     * <p>/docs, /analyze and /analysis are deliberately absent: those runs did no work and then
     * reported success, and neither kind is a build this system can verify. A command that is not
     * recognised is treated as ordinary chat, which is the honest outcome — nothing starts.
     */
    private static String commandKind(String text) {
        String head = text.split("\\s+", 2)[0].toLowerCase();
        return switch (head) {
            case "/run", "/greenfield" -> "GREENFIELD";
            case "/bugfix", "/fix" -> "BUGFIX";
            case "/refactor" -> "REFACTOR";
            default -> null;
        };
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
