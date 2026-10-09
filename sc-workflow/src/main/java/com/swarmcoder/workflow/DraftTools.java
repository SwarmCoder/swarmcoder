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

import com.swarmcoder.domain.DesignFinding;
import com.swarmcoder.knowledge.ExpertTools;
import com.swarmcoder.knowledge.LookupAgent;
import com.swarmcoder.runtime.AgentRuntime.ToolBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *
 * <p><b>The architect has a third tool, {@code keep_for_workers}</b> (owner's decision,
 * 2026-10-08; section 73). It marks part of a lookup the architect has just made as a finding
 * the workers are given: what it is about, which lookup, which lines of its result, and one
 * sentence. The lines are copied here from the session's own record of that lookup, so the
 * architect writes about thirty output tokens for a finding and never types code again. The other
 * way considered - a tool that takes the fact with its code as text - costs the code a second
 * time in output tokens, and output is what a run pays for; and it would let a finding carry
 * code no lookup returned. The planner has no such tool: it splits and orders.
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

    /** What the architect kept for the workers in this session, oldest first. */
    private final List<DesignFinding> kept = new ArrayList<>();
    private boolean remindedToKeep;

    /**
     * The most lines of a lookup's result one finding carries. Forty, not twenty (live run 100):
     * a whole small class of a reference example is 30 to 40 lines after its header, and input
     * tokens on the workers' server are cheap beside the lookups a worker makes without them.
     */
    static final int FINDING_LINES = 40;
    /** The most characters of code one finding carries; cut at a line's end. */
    static final int FINDING_SNIPPET_CHARS = 3_200;
    /** The most characters of the sentence. */
    static final int FINDING_NOTE_CHARS = 400;
    /** A safety stop, not a budget: how many findings one design may carry. */
    static final int MAX_FINDINGS = Integer.getInteger("swarmcoder.handover.maxFindings", 40);

    private static final Pattern LINE_RANGE = Pattern.compile("^(\\d+)\\s*-\\s*(\\d+)$");

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
                String keep = "";
                if ("design".equals(noun) && !remindedToKeep && findings().isEmpty()
                        && session.lookupsMade() > 0) {
                    // Said once, and not an objection: a design with nothing kept is still a
                    // design, and the workers then start from the librarian's brief.
                    remindedToKeep = true;
                    keep = " You have kept nothing for the workers: if a lookup of yours showed "
                        + "how this project or its framework does something a task will have "
                        + "to do, keep it with keep_for_workers first.";
                }
                return "NO OBJECTIONS: the mechanical checks found nothing wrong with this "
                    + noun + ". Hand it in: call report_done with an empty string." + keep
                    + remaining;
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
     * {@code keep_for_workers}. The parameter names are the tool's schema - do not rename them.
     *
     * @param about  the contract or type the fact is about; empty for the whole project
     * @param lookup a lookup made in this session: its tool, a space, its argument
     * @param lines  {@code first-last} within that lookup's result, empty for all of a short
     *               result, {@code none} for the sentence alone
     * @param note   the fact, in one sentence
     */
    public String keepForWorkers(String about, String lookup, String lines, String note) {
        return session.runOwnTool("keep_for_workers", lookup == null ? "" : lookup.strip(), () -> {
            if (note == null || note.isBlank()) {
                return "error: say the fact in one sentence, as note.";
            }
            Optional<Map.Entry<String, String>> found = session.resultOf(lookup);
            if (found.isEmpty()) {
                List<String> made = session.callsThatFound(8);
                return "error: no lookup of this session matches '" + (lookup == null ? ""
                    : lookup.strip()) + "'. Give lookup as the tool and its argument, exactly as "
                    + "you called it" + (made.isEmpty() ? "; you have made no lookup that found "
                    + "anything yet." : ". Your latest: " + String.join(" | ", made));
            }
            String wanted = lines == null ? "" : lines.strip();
            String[] all = found.get().getValue().strip().split("\\R", -1);
            String snippet = null;
            String took;
            if (wanted.equalsIgnoreCase("none")) {
                took = "the sentence alone";
            } else {
                int first = 1;
                int last = all.length;
                if (!wanted.isEmpty()) {
                    Matcher range = LINE_RANGE.matcher(wanted);
                    if (!range.matches()) {
                        return "error: give lines as first-last (for example 3-14), counted "
                            + "from the first line of that lookup's result; empty for all of a "
                            + "short result; none for the sentence alone.";
                    }
                    first = Math.max(1, Integer.parseInt(range.group(1)));
                    last = Math.min(all.length, Integer.parseInt(range.group(2)));
                    if (first > last) {
                        return "error: that lookup's result has " + all.length + " line(s); "
                            + wanted + " is outside it.";
                    }
                }
                // More lines than a finding carries: sent back, never cut to its first lines.
                // Live run 100: five of thirteen findings named a whole file from the line
                // after its licence header; each was kept as its first twenty lines - the
                // package line, the imports and a comment - and the workers given them read
                // the same five files again. The head of a range is not what the architect
                // meant, and only it knows which lines are.
                if (last - first + 1 > FINDING_LINES) {
                    return "NOT KEPT: " + (wanted.isEmpty() ? "that lookup's result has "
                        + all.length + " lines" : wanted + " is " + (last - first + 1)
                        + " lines") + ", and a finding carries at most " + FINDING_LINES
                        + ". Name the lines that DO the thing the note says (first-last, counted "
                        + "from the first line of that result) - not a file's package line, its "
                        + "imports or its comments. Or look the one member or type up on its "
                        + "own (body_of <Type>#<member>, body_of <Type>) and keep that result "
                        + "whole. Two places that both matter are two findings.";
                }
                boolean cut = false;
                StringBuilder text = new StringBuilder();
                int taken = 0;
                for (int i = first; i <= last; i++) {
                    if (text.length() + all[i - 1].length() + 1 > FINDING_SNIPPET_CHARS) {
                        cut = true;
                        break;
                    }
                    text.append(all[i - 1]).append('\n');
                    taken++;
                }
                snippet = text.toString().stripTrailing();
                took = taken == 0 ? "no line (the first one is longer than a finding carries)"
                    : "lines " + first + "-" + (first + taken - 1) + " of " + all.length
                    + (cut ? " (a finding carries at most " + FINDING_SNIPPET_CHARS
                        + " characters; keep the rest as a second finding if it matters)" : "");
            }
            String sentence = note.strip().replaceAll("\\s+", " ");
            if (sentence.length() > FINDING_NOTE_CHARS) {
                sentence = sentence.substring(0, FINDING_NOTE_CHARS);
            }
            String subject = about == null || about.isBlank() ? null : about.strip();
            String source = found.get().getKey();
            DesignFinding finding = new DesignFinding(UUID.randomUUID(), subject, source,
                sentence, snippet == null || snippet.isBlank() ? null : snippet);
            synchronized (kept) {
                // The same place kept again for the same subject replaces what was kept.
                kept.removeIf(k -> java.util.Objects.equals(k.about(), subject)
                    && java.util.Objects.equals(k.source(), source)
                    && java.util.Objects.equals(k.snippet(), finding.snippet()));
                if (kept.size() >= MAX_FINDINGS) {
                    return "error: this design already carries " + MAX_FINDINGS + " findings, "
                        + "the ceiling, so this one was not kept.";
                }
                kept.add(finding);
                log.info("keep_for_workers: [{}] from {} ({}), {} characters, {} kept",
                    subject == null ? "the whole project" : subject, source, took,
                    finding.size(), kept.size());
                return "KEPT for " + (subject == null ? "every task (the whole project)"
                    : "the tasks that build or change " + subject) + ": " + took + " of "
                    + source + ". " + kept.size() + " kept so far.";
            }
        });
    }

    /** What the architect kept for the workers in this session; a copy. */
    List<DesignFinding> findings() {
        synchronized (kept) {
            return List.copyOf(kept);
        }
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
            List<ToolBinding> tools = new ArrayList<>();
            if ("design".equals(noun)) {
                tools.add(new ToolBinding("keep_for_workers",
                    "Keep part of a lookup you have just made as a fact the workers are given "
                        + "with the tasks it concerns - word for word, in place of looking it "
                        + "up again. about: the contract or type the fact is about (its name as "
                        + "your design gives it), or empty for the whole project. lookup: the "
                        + "lookup it came from, as you called it - the tool, a space, its "
                        + "argument (for example: body_of OrderService#save). lines: first-last "
                        + "within that lookup's result (for example 3-14; at most "
                        + FINDING_LINES + " lines - the lines that do the thing, not a "
                        + "file's imports), empty for all of a short result, or none "
                        + "for the sentence alone. note: the fact in one sentence. You never "
                        + "type the code: the lines are copied from the lookup.",
                    this, DraftTools.class.getMethod("keepForWorkers", String.class,
                        String.class, String.class, String.class)));
            }
            tools.addAll(List.of(
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
                    this, DraftTools.class.getMethod("reportDone", String.class))));
            return tools;
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
