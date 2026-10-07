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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.AutonomousDecisionKind;
import com.swarmcoder.domain.FlowQuestion;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.inference.LlmJson;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Answers, on the operator's behalf, the clarifications the analyst and the planner ask - and writes
 * down that it did.
 *
 * <p><b>Read this before changing anything here.</b> A clarifying question is asked <em>because the
 * document did not say</em>. There is therefore usually nothing to retrieve, and what comes back is
 * not a lookup: it is a decision about what the product is. "Where are the books kept?" answered
 * with "a JSON file on disk" is a requirement the operator never wrote, and everything downstream -
 * the checks, the tests, the verdict, the delivered badge - will then honestly and completely prove
 * that invented requirement was built. That is the failure mode this class is shaped around.
 *
 * <p><b>So every answer says which of the two it was.</b> The model is asked for a flag, in the same
 * reply: did the passage you were shown actually answer this, or did you make it up? Both are
 * written to the night's diary and the made-up ones are the list the operator reads first. When the
 * model will not say, the answer is filed as made up - because an assumption recorded as a fact is
 * worse than no record at all.
 *
 * <p><b>What it is given.</b> Exactly what a person is given on that screen: the subject, the
 * question, the passage it arose from, the fuller background, and the closed-ended options where
 * there are any. Not the whole document - the analyst attached that passage precisely because it is
 * the part that matters, and re-sending megabytes per question would cost more than the analysis
 * did.
 *
 * <p><b>What it is told to prefer.</b> The smallest, most conventional, most reversible answer, and
 * an existing option over an invented one. Overnight is not the time to be interesting: a decision
 * the operator can undo in the morning is worth far more than one that happens to be clever.
 */
final class AutonomousAnswers {

    private static final Logger log = LoggerFactory.getLogger(AutonomousAnswers.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Enough for a decision and a reason. More than this is the model writing an essay. */
    private static final int MAX_ANSWER_CHARS = 600;

    private AutonomousAnswers() {}

    /** What one question's answering produced, for the caller's summary line. */
    record Outcome(int answered, int invented, int skipped) {
        Outcome plus(Outcome other) {
            return new Outcome(answered + other.answered, invented + other.invented,
                skipped + other.skipped);
        }
    }

    /**
     * Answers every open question on the flow, writing one diary line each, and returns what
     * happened.
     *
     * <p>A question the model cannot answer is SKIPPED rather than guessed at blindly. Skipping is
     * already first-class in both flows - the agent must then state the assumption it made in the
     * requirement or the story's rationale - so a skip stays visible and correctable, which a
     * fabricated answer with no flag on it would not.
     */
    static Outcome answerAll(ConsoleContext context, AutonomousMode.Session session,
                             GuidedFlow flow, boolean planning) {
        ArtifactStore store = context.store();
        List<FlowQuestion> questions = new ArrayList<>(store.listFlowQuestions(flow.id()));
        Outcome total = new Outcome(0, 0, 0);
        for (FlowQuestion question : questions) {
            if (question.answer() != null && !question.answer().isBlank()) {
                continue;   // already answered - by a person, or by an earlier tick
            }
            total = total.plus(answerOne(context, session, flow, question, planning));
        }
        store.saveFlowQuestions(flow.id(), questions);
        GuidedFlows.publish(store, flow);
        return total;
    }

    private static Outcome answerOne(ConsoleContext context, AutonomousMode.Session session,
                                     GuidedFlow flow, FlowQuestion question, boolean planning) {
        AskOutcome outcome = ask(context, question, planning);
        Reply reply = outcome.reply();
        String subject = question.subject() == null || question.subject().isBlank()
            ? "A question about your documents" : question.subject();
        if (reply == null) {
            question.setAnswer(null);
            question.setSkipped(true);
            String why = outcome.failureReason() != null ? outcome.failureReason()
                : "The model did not come back with a usable answer, so this was left unanswered "
                    + "rather than guessed at. Whatever was built assumed something here; the "
                    + "assumption is stated on the requirement it produced.";
            AutonomousMode.write(context.store(), session, AutonomousDecisionKind.REFUSED,
                subject, question.text(), "Skipped - no answer was given", why,
                true, flow.id(), question.id());
            return new Outcome(0, 0, 1);
        }
        question.setAnswer(reply.answer);
        question.setSkipped(false);
        // Said on the question itself as well as in the diary. The wizard shows notes beside the
        // answer, so somebody who opens the flow tomorrow sees who answered it without having to
        // know that a separate record exists.
        question.setNote(reply.grounded
            ? "Answered by SwarmCoder while running on its own, from what your documents said."
            : "DECIDED BY SWARMCODER, not by you. Your documents did not answer this, so it chose "
                + "one. " + reply.why);
        AutonomousMode.write(context.store(), session, AutonomousDecisionKind.ANSWERED_QUESTION,
            subject, question.text(), reply.answer, reply.why, reply.grounded,
            flow.id(), question.id());
        return new Outcome(1, reply.grounded ? 0 : 1, 0);
    }

    // --- the model call -------------------------------------------------------------------------

    /** One answer, or null when nothing usable came back. */
    private record Reply(String answer, boolean grounded, String why) {}

    /** What {@link #ask} produced: an answer, or — only when the reply never became readable
     * JSON, twice — the honest reason, which {@link #answerOne} writes to the diary in place of
     * the generic "no answer was given" line. */
    private record AskOutcome(Reply reply, String failureReason) {
        static AskOutcome of(Reply reply) {
            return new AskOutcome(reply, null);
        }
        static AskOutcome failed(String reason) {
            return new AskOutcome(null, reason);
        }
    }

    private static AskOutcome ask(ConsoleContext context, FlowQuestion question, boolean planning) {
        ConsoleContext.ChatModel model = planning ? context.plannerModel() : context.analystModel();
        if (model == null) {
            return AskOutcome.of(null);
        }
        List<Map<String, String>> messages = List.of(
            Map.of("role", "system", "content", system(planning)),
            Map.of("role", "user", "content", user(question)));
        try {
            String firstReply = call(model, messages);
            LlmReplyRetry.Asked<Reply> asked = LlmReplyRetry.askJson(context.blobStore(), "the model's",
                messages, firstReply, m -> call(model, m), r -> parseOrThrow(r, question));
            return AskOutcome.of(asked.value());
        } catch (MalformedReplyException e) {
            log.warn("Autonomous: could not answer \"{}\": {}", question.text(), e.getMessage());
            return AskOutcome.failed(e.getMessage());
        } catch (Exception e) {
            log.warn("Autonomous: could not answer \"{}\": {}", question.text(), e.toString());
            return AskOutcome.of(null);
        }
    }

    /** The model call itself, over an already-built conversation. */
    private static String call(ConsoleContext.ChatModel model, List<Map<String, String>> messages)
            throws Exception {
        StringBuilder out = new StringBuilder();
        try (Stream<String> stream = model.stream(messages, null)) {
            for (Iterator<String> it = stream.iterator(); it.hasNext(); ) {
                out.append(it.next());
            }
        }
        return out.toString();
    }

    private static String system(boolean planning) {
        return """
            You are standing in for a person who is asleep. They have switched SwarmCoder into a \
            mode where it decides for them, and you are answering one question they would have \
            answered themselves.

            Answer it in one or two sentences. Then say honestly whether the passage you were shown \
            actually answered it.

            The honesty flag is the important part of your reply and it is not a formality. If the \
            material you were given genuinely settles the question, set grounded to true. If it \
            does not - if you are choosing, inferring, or applying a convention - set grounded to \
            false. Answering false costs nothing: it puts the decision on a list the operator reads \
            in the morning. Answering true when you invented something is the one thing you must \
            never do, because it files an invention as their own instruction and nobody ever \
            checks it.

            When you have to choose, choose the smallest, most ordinary and most easily reversed \
            option. Prefer one of the options offered over anything new. Do not be ambitious and do \
            not add scope: you are unblocking one question, not designing the product.
            """
            + (planning
                ? "\nThis question is about how to slice agreed work into stories and what order to "
                + "build them in. Nothing you say here changes what the product must do.\n"
                : "\nThis question is about what the product must do. Whatever you answer becomes a "
                + "requirement that will be built and proved.\n")
            + """

            Reply with one JSON object and nothing else:
            {"answer": "...", "grounded": true, "why": "one sentence saying where it came from"}
            """;
    }

    private static String user(FlowQuestion question) {
        StringBuilder sb = new StringBuilder();
        if (question.subject() != null && !question.subject().isBlank()) {
            sb.append("SUBJECT: ").append(question.subject()).append('\n');
        }
        sb.append("QUESTION: ").append(question.text() == null ? "" : question.text()).append('\n');
        if (!question.options().isEmpty()) {
            sb.append("OPTIONS (prefer one of these, word for word): ")
                .append(String.join(" | ", question.options())).append('\n');
        }
        if (question.sourceQuote() != null && !question.sourceQuote().isBlank()) {
            sb.append("\nTHE PASSAGE THIS AROSE FROM");
            if (question.sourceDocument() != null && !question.sourceDocument().isBlank()) {
                sb.append(" (").append(question.sourceDocument()).append(')');
            }
            sb.append(":\n").append(question.sourceQuote()).append('\n');
        } else {
            sb.append("\nNo passage was attached to this question. That on its own is strong "
                + "evidence the documents do not answer it.\n");
        }
        if (question.background() != null && !question.background().isBlank()) {
            sb.append("\nBACKGROUND:\n").append(question.background()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Reads the model's reply, tolerantly ({@link LlmJson} — fenced, prose-prefixed, or
     * double-encoded, the same shapes {@code TestAuthorClient} and {@code ArchitectClient} accept).
     *
     * <p>Throws only when nothing in the reply reads as JSON at all — that is what earns a reply
     * the one retry {@link LlmReplyRetry} gives it. A reply that DOES read but carries no usable
     * "answer" is a different, unexceptional outcome (the model chose not to answer) and returns
     * null instead, same as before.
     *
     * <p>A missing or unreadable {@code grounded} field reads as FALSE. That is the whole point:
     * the expensive mistake is an invention filed as a fact, so the ambiguous case goes on the list
     * the operator reads rather than the one they do not.
     */
    private static Reply parseOrThrow(String reply, FlowQuestion question) throws IOException {
        if (reply == null || reply.isBlank()) {
            throw new IOException("empty reply");
        }
        JsonNode node = LlmJson.readTree(JSON, reply);
        String answer = text(node, "answer");
        if (answer.isBlank()) {
            return null;
        }
        if (answer.length() > MAX_ANSWER_CHARS) {
            answer = answer.substring(0, MAX_ANSWER_CHARS).strip() + "...";
        }
        boolean grounded = node.has("grounded") && node.get("grounded").isBoolean()
            && node.get("grounded").asBoolean();
        // A question with nothing quoted against it cannot have been answered from the documents,
        // whatever the model says about itself. The analyst attaches the passage when there is one.
        if (question.sourceQuote() == null || question.sourceQuote().isBlank()) {
            grounded = false;
        }
        String why = text(node, "why");
        return new Reply(answer, grounded, why.isBlank()
            ? "No reason was given." : why);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").strip();
    }
}
