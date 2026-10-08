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
package com.swarmcoder.workflow;

import com.swarmcoder.knowledge.ExpertTools;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.AgentRuntime.ToolBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;

/**
 * The architect's and the planner's own two tools in a lookup session ({@link LookupAgent}):
 * {@code check_design} or {@code check_plan}, which runs the build's own mechanical checks on a
 * draft and returns their objections BEFORE the draft is handed in, and {@code report_done}, which
 * hands it in.
 *
 * <p><b>Why.</b> Those checks used to speak only after the role had answered: a design that named a
 * library type which does not exist, or made a second copy of a type the project already has, cost
 * a whole revision round - a fresh one-reply call, minutes long on the local model - and a plan
 * that failed a mechanical check cost one of its three attempts. Here the role sees the same
 * objections, from the same checks, while it can still correct the draft and look up what it
 * guessed. The checks after hand-in are unchanged; this only moves the first reading earlier.
 *
 * <p>A draft is the JSON object the role always answered with, as one string. Nothing is written
 * anywhere by this class.
 */
public final class DraftTools {

    private static final Logger log = LoggerFactory.getLogger(DraftTools.class);

    /**
     * What the mechanical checks said about a draft.
     *
     * @param readable   false when the draft could not be read as the JSON object at all
     * @param objections empty when the checks have nothing to object to
     */
    public record Checked(boolean readable, List<String> objections) {

        static Checked unreadable(String why) {
            return new Checked(false, List.of(why));
        }

        boolean clean() {
            return readable && objections.isEmpty();
        }
    }

    private final ExpertTools session;
    /** "design" or "plan": the word the tool's name and its replies are made of. */
    private final String noun;
    private final int maxChecks;
    private volatile Function<String, Checked> check;

    private int checks;
    /** The last draft the checks could read. */
    private String lastReadable;
    private boolean lastWasClean;
    /** The last draft the checks had nothing to object to; null until there is one. */
    private String lastClean;
    /** What {@code report_done} was given, when it was given a draft of its own. */
    private String handedIn;
    private boolean done;

    DraftTools(ExpertTools session, String noun, int maxChecks, Function<String, Checked> check) {
        this.session = session;
        this.noun = noun;
        this.maxChecks = maxChecks;
        this.check = check;
    }

    /** {@code check_design}. The parameter name is the tool's schema - do not rename it. */
    public String checkDesign(String design) {
        return checkDraft(design);
    }

    /** {@code check_plan}. The parameter name is the tool's schema - do not rename it. */
    public String checkPlan(String plan) {
        return checkDraft(plan);
    }

    private String checkDraft(String draft) {
        String tool = "check_" + noun;
        return session.runOwnTool(tool, draft == null ? "" : draft.length() + " chars", () -> {
            if (draft == null || draft.isBlank()) {
                return "error: no " + noun + " was given. Call " + tool + " with the complete "
                    + "JSON object as its one string argument.";
            }
            if (checks >= maxChecks) {
                return "error: you have used all " + maxChecks + " checks, so this draft was not "
                    + "checked and not kept. Call report_done now: with an empty string it hands "
                    + "in the draft you last checked, or give it your final JSON object.";
            }
            checks++;
            Checked result = check.apply(draft);
            String remaining = checks < maxChecks ? ""
                : "\n\n(That was your last check: call report_done now.)";
            // What sent a draft back is on the log, objection by objection (live run 98: two
            // drafts were refused with an answer of the same length and nothing recorded which
            // check had spoken, so nobody could tell whether its wording could be acted on).
            if (!result.readable()) {
                log.info("{} draft {} ({} chars) could not be read: {}", tool, checks,
                    draft.length(), result.objections());
            } else {
                log.info("{} draft {} ({} chars): {} objection(s)", tool, checks, draft.length(),
                    result.objections().size());
                for (String objection : result.objections()) {
                    log.info("{} draft {} objection: {}", tool, checks, objection);
                }
            }
            if (!result.readable()) {
                return "NOT READ: " + String.join("; ", result.objections()) + "\nGive " + tool
                    + " the complete JSON object described in your instructions, as its one "
                    + "string argument." + remaining;
            }
            lastReadable = draft;
            lastWasClean = result.objections().isEmpty();
            if (lastWasClean) {
                lastClean = draft;
                return "NO OBJECTIONS: the mechanical checks found nothing wrong with this "
                    + noun + ". Hand it in: call report_done with an empty string." + remaining;
            }
            StringBuilder reply = new StringBuilder(result.objections().size() + " OBJECTION(S) "
                + "from the mechanical checks - each one would send this " + noun + " back after "
                + "you handed it in:\n");
            for (String objection : result.objections()) {
                reply.append("- ").append(objection).append('\n');
            }
            return reply + "Fix every one - look up whatever an objection shows you guessed - and "
                + "call " + tool + " again." + remaining;
        });
    }

    /**
     * The same session goes on after what it handed in was sent back (section 54): the next
     * hand-in is judged on its own, with the checks of the new call and a fresh allowance of
     * checks. What the role drafted before is forgotten here (it was rejected); its conversation
     * still holds it.
     */
    void nextRound(Function<String, Checked> check) {
        this.check = check;
        checks = 0;
        lastReadable = null;
        lastWasClean = false;
        lastClean = null;
        handedIn = null;
        done = false;
    }

    /** Ends the session. The parameter name is the tool's schema - do not rename it. */
    public String reportDone(String finalJson) {
        String given = finalJson == null ? "" : finalJson.strip();
        if (given.startsWith("{") || given.startsWith("[")) {
            handedIn = given;
        }
        done = true;
        return "handed in";
    }

    List<ToolBinding> bindings() {
        try {
            String tool = "check_" + noun;
            String method = "design".equals(noun) ? "checkDesign" : "checkPlan";
            return List.of(
                new ToolBinding(tool,
                    "Run the build's own mechanical checks on a draft of your " + noun + " and "
                        + "get their objections back, before you hand it in. Give the complete "
                        + "JSON object described in your instructions as one string. Check "
                        + "again after every correction.",
                    this, DraftTools.class.getMethod(method, String.class)),
                new ToolBinding(LookupAgent.SUBMIT_TOOL,
                    "Hand in your " + noun + " and end the session. Give an empty string to hand "
                        + "in the draft you last gave to " + tool + " exactly as it was, or the "
                        + "complete final JSON object if you changed it since.",
                    this, DraftTools.class.getMethod("reportDone", String.class)));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    boolean done() {
        return done;
    }

    int checks() {
        return checks;
    }

    /**
     * What is handed in, or null when the session produced no draft at all: the JSON given to
     * {@code report_done} when it was given one; otherwise the last checked draft when the checks
     * had nothing against it; otherwise the last draft they did pass, when there was one;
     * otherwise the last draft they could read, for the checks after hand-in to judge.
     */
    String submission() {
        if (handedIn != null) {
            return handedIn;
        }
        if (lastWasClean || lastClean == null) {
            return lastReadable;
        }
        return lastClean;
    }
}
