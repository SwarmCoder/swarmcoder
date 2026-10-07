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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.JudgeScore;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.RuleDispute;
import com.swarmcoder.domain.Task;
import com.swarmcoder.knowledge.ProjectRules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When every candidate breaks the same HARD rule, or two or more dispute it with evidence, the
 * question is about the RULE, not the code.
 *
 * <p><b>Why</b> (harness runs 53 and 55, 2026-10-01, HamBook on ZeroZ Stack 0.9.1). Both runs
 * parked hours in with "every candidate broke the stated rule ...": once over "all user-visible
 * text lives in resource bundles", which the stack cannot do yet, and once over "every type that
 * crosses the wire is a {@code @DataModel}", which the stack does not need for an enum. The park
 * said the code was wrong and asked for the plan to be fixed. It was the rule. Independent workers
 * all meeting the same wall is evidence about the wall.
 *
 * <p>So the operator is asked ONE question about the rule — keep it, reword it (a rewording is
 * suggested, drawn from the disputes and from what the code did), or allow what the candidates did
 * in this case — with the evidence in front of them. It travels as an ordinary pending decision
 * ({@code DecisionKind.GUIDELINE_REVIEW}, dormant until now), so the Console, the harness and MCP
 * all see it where they already look. The answer is remembered for the project through
 * {@link Amendments}, and the task re-selects from the candidates it already has.
 *
 * <p>Nobody is there to answer in an unattended run, so under
 * {@link com.swarmcoder.domain.OpinionPolicy#WARN_AND_CARRY_ON} — the one policy every opinion
 * check reads — it is answered on the spot with the suggested rewording, the decision is recorded
 * RESOLVED with its evidence on the run, and the run carries on.
 *
 * <p>Only a HARD rule's break raises this. A preference never stops anything — see
 * {@link JudgeClient#classify}.
 *
 * <p><b>When the evidence points at an earlier task's file, it is not a question about the rule</b>
 * (harness run 65, 2026-10-02 — see {@link SiblingDefects}). Unattended, the task is given that
 * file to repair and built again once, and no question is raised at all. Attended, the question is
 * still put, with a fourth answer: {@code repair}. Only after that one repair has been tried does
 * a dispute over the same rule become the plain question above.
 */
public final class RuleQuestions {

    /** How an answer about a rule is remembered for the project. Implemented over {@link ProjectRules}. */
    public interface Amendments {

        /** Nothing wired: every answer is refused, so a question can only park. */
        Amendments NONE = new Amendments() {
            @Override public String reword(UUID ruleId, String newWording, String why) {
                return "error: nothing can change this project's rules from here";
            }
            @Override public String allowException(UUID ruleId, String exception) {
                return "error: nothing can change this project's rules from here";
            }
            @Override public String keep(UUID ruleId) {
                return "error: nothing can change this project's rules from here";
            }
        };

        /** @return "" on success, else "error: …" */
        String reword(UUID ruleId, String newWording, String why);

        /** @return "" on success, else "error: …" */
        String allowException(UUID ruleId, String exception);

        /** @return "" on success, else "error: …" */
        String keep(UUID ruleId);

        /** The project's own rules. */
        static Amendments over(ProjectRules rules) {
            return new Amendments() {
                @Override public String reword(UUID ruleId, String newWording, String why) {
                    return rules.reword(ruleId, newWording, why);
                }
                @Override public String allowException(UUID ruleId, String exception) {
                    return rules.allowException(ruleId, exception);
                }
                @Override public String keep(UUID ruleId) {
                    return rules.keep(ruleId);
                }
            };
        }
    }

    /**
     * The answers a question about a rule can have. REPAIR is offered only when the evidence named
     * an earlier task's file: the rule stands, and the task is built again with that file added
     * to what it may edit.
     */
    enum AnswerKind { KEEP, REWORD, ALLOW, REPAIR }

    /** An answer, with the new wording when the operator wrote one. */
    record Answer(AnswerKind kind, String wording) {}

    /** Per dispute's evidence in the brief: enough to judge it, not a log dump. */
    private static final int EVIDENCE_IN_BRIEF = 600;

    static final String SUGGESTED_PREFIX = "SUGGESTED REWORDING: ";
    static final String EXCEPTION_PREFIX = "EXCEPTION IF ALLOWED: ";
    static final String REPAIR_FILES_PREFIX = "FILES TO REPAIR: ";
    static final String REPAIR_NOTE_PREFIX = "REPAIR NOTE: ";
    private static final Pattern REF = Pattern.compile(
        "\\(question ref: task ([0-9a-fA-F-]{36}), rule ([0-9a-fA-F-]{36}|none)\\)");

    /**
     * One question about one rule.
     *
     * @param rule      the rule object, or null when no rule objects were wired in (the question
     *                  can then be asked but not answered automatically)
     * @param ruleName  the rule as a person reads it
     * @param breaks    "worker N: the judge's sentence" for each candidate that broke it
     * @param disputes  "worker N" → the dispute, for each candidate that disputed it
     * @param defect    the earlier task's file the disputes point at, when they point at one and
     *                  the task has not been given it to repair yet; else null
     */
    record Question(Task task, LearnedGuideline rule, String ruleName, List<String> breaks,
                    List<WorkerDispute> disputes, boolean afterRepair,
                    SiblingDefects.Defect defect) {

        /** The same question, offering the repair of an earlier task's file as an answer. */
        Question offering(SiblingDefects.Defect found) {
            return new Question(task, rule, ruleName, breaks, disputes, afterRepair, found);
        }

        /** A rewording that allows what the evidence showed, drawn from the disputes and the code. */
        String suggestedRewording() {
            String wording = rule == null || rule.markdownBody() == null
                ? ruleName : rule.markdownBody().strip();
            return wording + "\nThis does not apply where the library itself makes it impossible "
                + "or unnecessary — found while building '" + task.title() + "': " + evidence()
                + ".";
        }

        /** The exception line an "allow" answer adds to the rule. */
        String exceptionText() {
            return "allowed for '" + task.title() + "': " + evidence();
        }

        /** What the candidates showed, in one line: their reasons if they disputed, else what they did. */
        private String evidence() {
            Set<String> parts = new LinkedHashSet<>();
            for (WorkerDispute dispute : disputes) {
                parts.add(oneLine(dispute.dispute().reason()));
            }
            if (parts.isEmpty()) {
                for (String broke : breaks) {
                    String said = broke.substring(broke.indexOf(':') + 1).strip();
                    String afterRule = said.contains(":") ? said.substring(said.indexOf(':') + 1)
                        : said;
                    parts.add(oneLine(afterRule));
                    if (parts.size() >= 3) {
                        break;
                    }
                }
            }
            return String.join("; ", parts);
        }

        /** What the operator reads — the pending decision's text, verbatim. */
        String brief() {
            StringBuilder sb = new StringBuilder("A QUESTION ABOUT A RULE, not about the code — task '")
                .append(task.title()).append("'.\n\n");
            if (disputes.size() >= 2) {
                sb.append(disputes.size()).append(" candidates independently disputed the stated rule '")
                    .append(ruleName).append("' with evidence, and none kept it.\n");
            } else {
                sb.append("every candidate broke the stated rule '").append(ruleName).append("'")
                    .append(afterRepair ? ", and every one of them had passed verification" : "")
                    .append(".\n");
            }
            if (rule != null && rule.markdownBody() != null) {
                sb.append("The rule: ").append(oneLine(rule.markdownBody())).append('\n');
            }
            sb.append("Why it exists: ").append(rule == null || rule.purpose() == null
                || rule.purpose().isBlank() ? "(not recorded)" : oneLine(rule.purpose()))
                .append("\n\n");
            if (!breaks.isEmpty()) {
                sb.append("What the candidates did:\n");
                for (String broke : breaks) {
                    sb.append("  - ").append(broke).append('\n');
                }
            }
            if (!disputes.isEmpty()) {
                sb.append("What the workers said about the rule:\n");
                for (WorkerDispute d : disputes) {
                    String evidence = d.dispute().evidence().strip();
                    if (evidence.length() > EVIDENCE_IN_BRIEF) {
                        evidence = evidence.substring(0, EVIDENCE_IN_BRIEF) + "…";
                    }
                    sb.append("  - worker ").append(d.workerIndex()).append(": ")
                        .append(oneLine(d.dispute().reason())).append('\n')
                        .append("    evidence: ").append(evidence.replace("\n", "\n      "))
                        .append('\n');
                }
            }
            if (defect != null) {
                sb.append("\nTHE EVIDENCE POINTS AT AN EARLIER TASK'S OUTPUT, NOT AT THE RULE: ")
                    .append(defect.named()).append(". This task may not edit it.\n");
            }
            sb.append("\nNothing was delivered for this task yet. Answer with one of:\n");
            if (defect != null) {
                sb.append("  repair — the rule stands; this task may also edit the file(s) named "
                    + "above and is built again once to fix them (suggested)\n");
            }
            sb.append("  keep — the rule stands as written; the task is built again under it\n")
                .append("  reword: <new wording> — the rule changes for the whole project (or just "
                    + "\"reword\" to take the suggestion below)\n")
                .append("  allow — this task may do what the candidates did; the rule gains that "
                    + "exception\n\n")
                .append(SUGGESTED_PREFIX).append(oneLine(suggestedRewording())).append('\n')
                .append(EXCEPTION_PREFIX).append(oneLine(exceptionText())).append('\n');
            if (defect != null) {
                sb.append(REPAIR_FILES_PREFIX).append(String.join(", ", defect.files()))
                    .append('\n')
                    .append(REPAIR_NOTE_PREFIX).append(oneLine(defect.instructions())).append('\n');
            }
            sb.append("(question ref: task ").append(task.id()).append(", rule ")
                .append(rule == null || rule.id() == null ? "none" : rule.id().toString())
                .append(")");
            return sb.toString();
        }
    }

    /** A dispute, with the worker that raised it. */
    record WorkerDispute(int workerIndex, RuleDispute dispute) {}

    private RuleQuestions() {}

    /**
     * The question these judged candidates raise about a rule, or null when they raise none.
     *
     * <p>A rule is in question when NO candidate kept it — each either broke it (a HARD break) or
     * its worker disputed it — and either two or more workers disputed it independently, or
     * ({@code afterRepair}) every candidate that passed verification broke it. The name is from
     * when a repair round came first; since 2026-10-02 none does (a task with a passing candidate
     * is not rebuilt on the judge's opinion), and the flag only says the break was unanimous. A
     * sibling that kept the rule proves it can be kept here, so then there is no question: the
     * rule-keeper wins and the disputes stay on record.
     *
     * @param rules the ACTIVE rules as objects; empty when none were wired in, in which case a
     *              rule is told apart by the name the judge wrote and every break counts as hard
     */
    static Question find(Task task, List<CandidateSolution> judged, List<LearnedGuideline> rules,
                         boolean afterRepair) {
        if (judged == null || judged.isEmpty()) {
            return null;
        }
        List<LearnedGuideline> ruleSet = rules == null ? List.of() : rules;
        // Every rule anybody broke or disputed, in the order first met, as one sentence naming it.
        List<String> named = new ArrayList<>();
        for (CandidateSolution candidate : judged) {
            named.addAll(hardBreaks(candidate));
            for (RuleDispute dispute : candidate.ruleDisputes()) {
                named.add(dispute.rule());
            }
        }
        for (String probe : named) {
            LearnedGuideline rule = RuleMatch.named(probe, ruleSet);
            if (!ruleSet.isEmpty() && (rule == null || !rule.hard())) {
                continue; // only a HARD rule's break can stop a task, so only it raises this
            }
            List<String> breaks = new ArrayList<>();
            List<WorkerDispute> disputes = new ArrayList<>();
            boolean everyCandidateBrokeIt = true;
            boolean noneKeptIt = true;
            for (CandidateSolution candidate : judged) {
                String broke = hardBreaks(candidate).stream()
                    .filter(entry -> names(entry, probe, rule, ruleSet)).findFirst().orElse(null);
                RuleDispute disputed = candidate.ruleDisputes().stream()
                    .filter(d -> names(d.rule(), probe, rule, ruleSet)).findFirst().orElse(null);
                if (broke != null) {
                    breaks.add("worker " + candidate.workerIndex() + ": " + broke);
                } else {
                    everyCandidateBrokeIt = false;
                }
                if (disputed != null) {
                    disputes.add(new WorkerDispute(candidate.workerIndex(), disputed));
                }
                if (broke == null && disputed == null) {
                    noneKeptIt = false;
                }
            }
            if (!noneKeptIt) {
                continue;
            }
            if (disputes.size() >= 2 || afterRepair && everyCandidateBrokeIt) {
                String name = rule != null ? RuleMatch.nameOf(rule) : RuleMatch.headOf(probe);
                return new Question(task, rule, name, breaks, disputes, afterRepair, null);
            }
        }
        return null;
    }

    /**
     * The same candidates with the answered rule's breaks moved out of the way — the rule was
     * reworded or gained an exception that allows what they did, so selection re-runs on them
     * without that rule counting against any of them. Every other finding is untouched.
     */
    static List<CandidateSolution> allowedUnder(String ruleName, LearnedGuideline rule,
                                                List<LearnedGuideline> rules,
                                                List<CandidateSolution> candidates,
                                                String because) {
        List<LearnedGuideline> ruleSet = rules == null ? List.of() : rules;
        List<CandidateSolution> out = new ArrayList<>();
        for (CandidateSolution candidate : candidates) {
            JudgeScore judge = candidate.judge();
            if (judge == null) {
                out.add(candidate);
                continue;
            }
            List<String> stillBroken = new ArrayList<>();
            List<String> disputed = new ArrayList<>(judge.disputedRules());
            for (String entry : judge.brokenRules()) {
                if (names(entry, ruleName, rule, ruleSet)) {
                    disputed.add(entry + " — " + because);
                } else {
                    stillBroken.add(entry);
                }
            }
            JudgeScore copy = new JudgeScore(judge.score(), judge.rationale(), judge.judgeModelId());
            copy.setBrokenRules(stillBroken);
            copy.setPreferenceBreaks(judge.preferenceBreaks());
            copy.setDisputedRules(disputed);
            out.add(new CandidateSolution(candidate.id(), candidate.taskId(),
                candidate.workerIndex(), candidate.branch(), candidate.sampling(),
                candidate.diffUnified(), candidate.verification(), candidate.cluster(), copy,
                candidate.state(), candidate.killReason()).carryingAuditFrom(candidate));
        }
        return out;
    }

    /**
     * The operator's answer, read from the decision's free-text response. Anything that is not
     * recognisably "reword", "allow" or "repair" is KEEP: the rule stands and nothing about it changes
     * silently.
     */
    static Answer parse(String response) {
        String text = response == null ? "" : response.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("reword")) {
            String wording = text.substring("reword".length()).strip();
            if (wording.startsWith(":") || wording.startsWith("-") || wording.startsWith("—")) {
                wording = wording.substring(1).strip();
            }
            return new Answer(AnswerKind.REWORD, wording);
        }
        if (lower.startsWith("allow")) {
            return new Answer(AnswerKind.ALLOW, "");
        }
        if (lower.startsWith("repair")) {
            return new Answer(AnswerKind.REPAIR, "");
        }
        return new Answer(AnswerKind.KEEP, "");
    }

    /** The task id a question's brief was raised for, or null when it is not a rule question. */
    static UUID taskOf(String brief) {
        Matcher m = brief == null ? null : REF.matcher(brief);
        return m != null && m.find() ? UUID.fromString(m.group(1)) : null;
    }

    /** The rule id a question's brief names, or null when it named none. */
    static UUID ruleOf(String brief) {
        Matcher m = brief == null ? null : REF.matcher(brief);
        return m != null && m.find() && !"none".equals(m.group(2)) ? UUID.fromString(m.group(2))
            : null;
    }

    /** A line of the brief that starts with {@code prefix}, without the prefix; "" when absent. */
    static String lineOf(String brief, String prefix) {
        if (brief == null) {
            return "";
        }
        for (String line : brief.split("\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).strip();
            }
        }
        return "";
    }

    private static List<String> hardBreaks(CandidateSolution candidate) {
        return candidate.judge() == null ? List.of() : candidate.judge().brokenRules();
    }

    /** Whether {@code sentence} names the same rule as {@code probe} (or as {@code rule}). */
    private static boolean names(String sentence, String probe, LearnedGuideline rule,
                                 List<LearnedGuideline> ruleSet) {
        if (rule != null) {
            return RuleMatch.named(sentence, ruleSet) == rule;
        }
        return RuleMatch.sameRule(sentence, probe, ruleSet);
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s*\\R\\s*", " ");
    }
}
