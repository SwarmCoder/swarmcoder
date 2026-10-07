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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.knowledge.ContractDelivery;
import com.swarmcoder.knowledge.ProjectTypes;
import com.swarmcoder.runtime.PathPolicy;
import com.swarmcoder.verify.BrowserOnlyCode;
import com.swarmcoder.verify.BuildLayout;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The PLAN-stage mechanical invariants (spec §14, rule R6): a TaskGraph is rejected — and
 * regenerated — unless it is acyclic and concurrently schedulable tasks have disjoint write
 * sets. Disjoint write ownership is "the most important line in the schema" (architecture
 * §4.3): it is what makes cross-task integration conflict-free by construction.
 *
 * <p>It also ORDERS what the plan's own types require (harness run 39, 2026-09-25): a task whose
 * code uses a type another task writes gets a dependency on that task when the plan left one out —
 * see {@link TypeDependencyOrder}. Like {@link #normalizeOneCheckOneTask} this mutates the graph
 * it is given, reports each change as a warning, and reports as a violation only what no
 * mechanical change can fix.
 *
 * <p><b>Every violation says how to satisfy it</b> (harness run 40, 2026-09-26). A violation is
 * not only a verdict: it is the text the planner reads on its next attempt, verbatim, and nothing
 * else in that prompt tells it what an acceptable fix looks like. Run 40 parked after three
 * attempts because "it is an enabler nothing builds on" named the fault and none of the ways out,
 * and the planner fixed one thing by breaking another and then broke the first thing again. So each
 * message here ends with the remedy in plain words — for the enabler rule, the specific choices
 * that exist in THIS plan ({@link #checkEnablersAreUsed(TaskGraph, StoryScope)}). A new violation
 * added to this class must do the same.
 *
 * <p>Criteria presence (≥1 executable acceptance criterion per task) is reported as a
 * WARNING until the test-author role exists (M3) — enforcing it today would reject every
 * graph the current planner can produce.
 */
public final class TaskGraphValidator {

    public record Verdict(List<String> violations, List<String> warnings) {
        public boolean ok() {
            return violations.isEmpty();
        }
    }

    /**
     * Which modules of the build run only in a browser — what {@link #unusedEnablers} needs to
     * tell a UI task no acceptance test can ever prove from a real orphan (harness run 49,
     * 2026-09-30). {@link BrowserOnlyCode.Survey#NONE} for a validator built without one, and
     * then no task is exempt.
     */
    private final BrowserOnlyCode.Survey browserOnly;

    public TaskGraphValidator() {
        this(BrowserOnlyCode.Survey.NONE);
    }

    /**
     * A validator that knows which modules run only in a browser. PLAN builds one per run from the
     * same survey the planner is shown, and uses that one instance for every check of that run —
     * each attempt, the last-attempt drop, and the re-check of the stored plan — so all of them
     * agree on what an enabler nothing builds on is.
     */
    public TaskGraphValidator(BrowserOnlyCode.Survey browserOnly) {
        this.browserOnly = browserOnly == null ? BrowserOnlyCode.Survey.NONE : browserOnly;
    }

    public Verdict validate(TaskGraph graph) {
        return validate(graph, null);
    }

    public Verdict validate(TaskGraph graph, StoryScope scope) {
        return validate(graph, scope, null);
    }

    /**
     * Validates a graph against the story it is meant to deliver.
     *
     * <p>Adds the COVERAGE invariant: every criterion in the story's slice must be claimed by at
     * least one task, and no task may claim one from outside it. This is a violation, not a
     * warning — an unclaimed criterion means the story can never legitimately reach REVIEW, so
     * accepting the graph would guarantee a run that looks successful and delivers less than it
     * promised. A claim from outside the slice is the planner widening its own scope.
     */
    public Verdict validate(TaskGraph graph, StoryScope scope, BuildLayout.Layout layout) {
        return validate(graph, scope, layout, null);
    }

    /**
     * The same, with the design whose contracts the plan must account for.
     *
     * <p>Adds the VOCABULARY invariant: every contract that names a type is delivered by exactly
     * one task. A contract nobody delivers is a type the acceptance tests are written against and
     * nobody builds — the failure of run 13, which only became visible three waves in as a test
     * that could not compile and a candidate blamed for a tree it had not broken. It is a
     * violation rather than a warning for the same reason coverage is: the plan can be sent back
     * and rewritten now, and cannot be after the swarm has run.
     */
    public Verdict validate(TaskGraph graph, StoryScope scope, BuildLayout.Layout layout,
                            DesignDocument design) {
        return validate(graph, scope, layout, design, null);
    }

    /**
     * The same, told which checkout on disk the plan is layered onto, so that a contract naming a
     * type the checkout already has is not held to the VOCABULARY invariant the way a genuinely new
     * type is.
     *
     * <p><b>Why this exists</b> (brownfield harness run 43, 2026-09-26, target {@code jsoup}, an
     * existing plain-Java library). The story was a bug fix to {@code org.jsoup.parser.Parser},
     * and the design named it — {@code NamespaceXml} is a constant the fix's acceptance test reads
     * off the real class — because that is genuinely the type the test is written against. Attempt
     * 1 was rejected here with "no task delivers the contract
     * {@code org.jsoup.parser.Parser{String NamespaceXml; }}", exactly as {@link
     * #checkEveryContractIsDelivered} was written to reject run 13's missing {@code Rating}. But
     * {@code Parser} and {@code NamespaceXml} were never missing: they were sitting in the checkout
     * before the architect ever ran. {@link #checkEveryContractIsDelivered}'s VOCABULARY rule was
     * built entirely for greenfield, where every named type is new by definition — nobody had yet
     * pointed it at a repository where a named type can simply already be there. The architect
     * worked around it on attempt 2 by inventing a task whose only job was to "declare" a type that
     * needed no declaring, which burns a planner attempt on every brownfield run for no reason, and
     * would have forced a worker to touch a file it had no real work to do in.
     *
     * <p>{@code repoRoot} is null for a greenfield run (there is no checkout yet to consult, so
     * every contract is new by construction — behaviour is exactly what it was before this
     * parameter existed) and the target repository's root for a brownfield one, the same tree
     * {@link AcceptanceTestVocabulary} and {@link com.swarmcoder.knowledge.ContractDelivery} already
     * read this way, at TEST_AUTHORING and at verification respectively — this is PLAN reading the
     * checkout with the same tool, one stage earlier.
     */
    public Verdict validate(TaskGraph graph, StoryScope scope, BuildLayout.Layout layout,
                            DesignDocument design, Path repoRoot) {
        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (graph.tasks() == null || graph.tasks().isEmpty()) {
            violations.add("task graph has no tasks — return at least one task that claims the story's "
                + "checks");
            return new Verdict(violations, warnings);
        }

        Set<UUID> ids = new HashSet<>();
        for (Task task : graph.tasks()) {
            ids.add(task.id());
        }
        for (TaskEdge edge : edges(graph)) {
            if (!ids.contains(edge.from()) || !ids.contains(edge.to())) {
                violations.add("edge references unknown task: " + edge.from() + " -> " + edge.to()
                    + " — every edge's from and to must be the id of a task in this same plan; "
                    + "remove the edge or correct the id");
            }
        }

        List<Task> stuck = tasksInOrBehindACycle(graph);
        boolean cyclic = !stuck.isEmpty();
        if (cyclic) {
            // Harness run 40, 2026-09-26: every objection says how to satisfy it. A bare "has a
            // dependency cycle" left the planner to find the loop itself.
            violations.add("task graph has a dependency cycle among (or behind) these tasks: "
                + stuck.stream().map(t -> "'" + t.title() + "'").collect(Collectors.joining(", "))
                + " — an edge runs from the task that must finish first to the task that needs "
                + "it, so remove whichever edge makes a task wait for work that itself waits for "
                + "that task");
        } else {
            // Before disjointness and before one-check-one-task, because both read the dependency
            // relation this may extend: a task whose code uses a type another task writes must run
            // after it (harness run 39, 2026-09-25 — "Create shared BooksService interface" was
            // planned beside "Create shared data model classes (Book and Rating)" with no edge
            // between them, and its workers could not compile a line). Mechanical, so the missing
            // edge is added and said out loud; only what cannot be ordered goes back to the
            // planner. See TypeDependencyOrder for when it adds, when it objects, and when it
            // leaves a plan alone.
            TypeDependencyOrder.Outcome typeOrder = TypeDependencyOrder.apply(graph, design);
            typeOrder.added().forEach(line -> warnings.add("added dependency: " + line));
            typeOrder.notes().forEach(line -> warnings.add("dependency not added: " + line));
            violations.addAll(typeOrder.violations());
            checkWriteSetDisjointness(graph, violations);
        }

        checkWriteSetIsWritable(graph, violations);
        checkWriteSetIsInTheBuild(graph, layout, violations, warnings);
        checkEveryContractIsDelivered(graph, design, scope, repoRoot, violations);
        // Live run 74, 2026-10-03: a change to an existing type that breaks files the task may
        // not write is a task no candidate can pass. See ChangeBreaksExistingCode.
        violations.addAll(ChangeBreaksExistingCode.objections(graph, repoRoot));

        if (scope != null && !scope.isEmpty()) {
            // Before coverage and the one-class-one-task check: normalising duplicate claims here
            // means both of those see a graph where every check already has exactly one owner,
            // rather than having to reason about the duplicate themselves. Skipped on a cyclic
            // graph — the dependency relation it reads is meaningless there, and the cycle
            // violation above already sends the plan back.
            if (!cyclic) {
                normalizeOneCheckOneTask(graph, scope, violations, warnings);
            }
            checkCriterionCoverage(graph, scope, violations);
            checkOneTestClassOneTask(graph, scope, violations);
            violations.addAll(checkEnablersAreUsed(graph, scope, design));
        } else {
            for (Task task : graph.tasks()) {
                if (task.criteria() == null || task.criteria().isEmpty()) {
                    warnings.add("task '" + task.title() + "' has no executable acceptance criteria "
                        + "(hard requirement once TEST_AUTHORING is real — spec §14)");
                }
            }
        }
        return new Verdict(violations, warnings);
    }

    /**
     * Every contract that names a type is delivered by exactly one task.
     *
     * <p>Silent when the design names no types at all — a design from before contracts carried
     * them, or a model that answered without them — because there is then no vocabulary to hold
     * anyone to, and inventing one would reject every plan.
     *
     * <p><b>A contract that names the acceptance test class itself is not held to this rule</b>
     * (harness run 19, 2026-09-03: the architect listed {@code swarm.accept.PersistenceTest} —
     * the class the check {@code retainsDataAfterRestart} is written against — as a contract, and
     * this rule then demanded a task deliver it. No task ever may: acceptance tests live under
     * the protected directory and only the test author writes them, so a plan that assigned one
     * would be rejected by {@code checkWriteSetIsWritable} the moment it tried, and a plan that
     * did not would loop back here forever. {@link AcceptanceTestContracts} tells this shape apart
     * from a real contract; this ordinarily does not fire in practice because the design is
     * already normalised at intake ({@code ArchitectClient}), but the rule holds independently of
     * that so a stored or hand-built design gets the same protection.
     *
     * <p><b>A contract whose type already exists in the checkout needs no delivering task</b>
     * (brownfield harness run 43, 2026-09-26, target {@code jsoup}). This rule was written for
     * greenfield, where every named type is new and "nobody delivers it" can only mean "nobody will
     * ever create it". On an existing codebase a design may legitimately name a type — and members
     * of it — that the checkout already has, because the change is a fix to existing code and the
     * acceptance test is written against the real thing. When {@code repoRoot} is given, a contract
     * with no delivering task is checked against that checkout with {@link
     * com.swarmcoder.knowledge.ContractDelivery} — the exact scanner {@link AcceptanceTestVocabulary}
     * and verification already read the same tree with — before being rejected:
     *
     * <ul>
     *   <li>the type exists and every member the contract names (that the scanner could read) is
     *       already declared on it — the contract is satisfied by the checkout as it stands, and no
     *       task needs to "deliver" code that is already there;</li>
     *   <li>the type exists but names a member the scanner finds nowhere on it — a task must still
     *       be told to add that member, so this is still a violation, worded around the member
     *       rather than the type;</li>
     *   <li>the type does not exist in the checkout either — unchanged: the ordinary "no task
     *       delivers the contract" objection below, exactly as greenfield has always produced it.</li>
     * </ul>
     *
     * <p>Member text that {@link com.swarmcoder.knowledge.JavaSourceFacts} cannot parse out of a
     * declared type is never held against a task: {@link com.swarmcoder.knowledge.ContractDelivery}
     * treats a type with no readable members as telling us nothing about its members at all, not as
     * telling us they are absent, and this rule inherits that same generosity by calling into it
     * rather than re-reading the source itself. Rejecting a plan on a scanner's blind spot would be
     * exactly the false accusation {@link AcceptanceTestVocabulary} was already written to avoid.
     * {@code repoRoot} is null for a greenfield run, and then this whole check is skipped exactly as
     * it always was — every named contract needs a delivering task, with no exception.
     */
    private static void checkEveryContractIsDelivered(TaskGraph graph, DesignDocument design,
                                                      StoryScope scope, Path repoRoot,
                                                      List<String> violations) {
        if (design == null || design.contracts() == null) {
            return;
        }
        List<String> checkTestRefs = scope == null ? List.of() : scope.criteria().stream()
            .map(c -> c == null ? null : c.testClassOrFile()).toList();
        List<ApiContract> named = design.contracts().stream()
            .filter(c -> c != null && c.namesAType())
            .filter(c -> !AcceptanceTestContracts.isAcceptanceTestClass(c, checkTestRefs))
            .toList();
        if (named.isEmpty()) {
            return;
        }
        ProjectTypes existingTypes = repoRoot == null ? null : ProjectTypes.of(repoRoot);
        for (ApiContract contract : named) {
            List<String> deliveredBy = new ArrayList<>();
            for (Task task : graph.tasks()) {
                for (ApiContract claimed : task.deliveredContracts()) {
                    if (claimed != null && contract.typeName().equalsIgnoreCase(claimed.typeName())) {
                        deliveredBy.add(task.title());
                    }
                }
            }
            if (deliveredBy.isEmpty()) {
                if (existingTypes != null) {
                    List<ContractDelivery.Shortfall> shortfalls =
                        ContractDelivery.shortfalls(existingTypes, List.of(contract));
                    if (shortfalls.isEmpty()) {
                        // The checkout already has this type, with every member this contract
                        // names that could be read out of it — brownfield, and nothing here is
                        // new work. See the class javadoc above for why this must not be held to
                        // "no task delivers the contract" the way run 13's genuinely missing
                        // Rating was.
                        continue;
                    }
                    ContractDelivery.Shortfall shortfall = shortfalls.get(0);
                    if (!shortfall.missingType()) {
                        // The type is real; a member the contract names is not on it yet — some
                        // task must still add it, so this remains a violation, but one about the
                        // member, not the type the checkout already has.
                        boolean plural = shortfall.missingMembers().size() > 1;
                        violations.add("no task delivers " + (plural ? "the members " : "the member ")
                            + String.join(", ", shortfall.missingMembers()) + " of "
                            + contract.typeName() + " — that type already exists in this checkout, "
                            + "but not with " + (plural ? "those members" : "that member")
                            + ", and the acceptance tests will be written against "
                            + contract.describe() + ". Put it in the deliversContracts of the "
                            + "task that adds " + (plural ? "them" : "it") + "."
                            + (shortfall.unannotated().isEmpty() ? ""
                                : " Part of this is an annotation the existing type does not "
                                    + "carry; that is a CHANGE to code that exists, so the task "
                                    + "that takes it must have the type's file in its write set "
                                    + "and say in its instructions that it adds the annotation "
                                    + "(harness run 78: a design stated one the real class never "
                                    + "had, and nothing could meet it unasked)."));
                        continue;
                    }
                    // shortfall.missingType(): the checkout does not have this type either, so
                    // this falls through to the ordinary objection below.
                }
                violations.add("no task delivers the contract " + contract.describe()
                    + " — the acceptance tests will be written against that type, so a plan that "
                    + "creates it nowhere produces tests that can never compile. Put it in the "
                    + "deliversContracts of the task that creates it.");
            } else if (deliveredBy.size() > 1) {
                violations.add("the contract " + contract.describe() + " is claimed by "
                    + deliveredBy.size() + " tasks (" + String.join(", ", deliveredBy)
                    + "). One type is one file and one task writes it; two tasks writing it "
                    + "means whichever wins last decides what it looks like. Keep it in the "
                    + "deliversContracts of the one task that writes that type's file, and "
                    + "remove it from the others.");
            }
        }
    }

    /**
     * A task must be able to write SOMETHING. A write set every entry of which the path policy
     * refuses describes work no worker can ever do.
     *
     * <p>Found live on 2026-08-28. The planner produced two tasks: one to implement the
     * multiplication, and one to "implement acceptance tests for multiplication" whose write set
     * was the acceptance-test file itself. Acceptance tests are written by the test author and are
     * protected from workers precisely so a worker cannot make its own gate pass — so the second
     * task was impossible by construction. Nothing checked it. Two workers were dispatched at it,
     * every edit they made was reverted as out of policy, they produced nothing, and the run spent
     * its entire fifteen-minute budget on work that could not be completed.
     *
     * <p>This is a violation rather than a warning for the same reason coverage is: the graph is
     * regenerated, and the single-task fallback behind that has no write set at all, so there is
     * always a way forward. Accepting the graph guarantees a run that burns out.
     */
    private static void checkWriteSetIsWritable(TaskGraph graph, List<String> violations) {
        for (Task task : graph.tasks()) {
            Set<String> writeSet = task.writeSet();
            if (writeSet == null || writeSet.isEmpty()) {
                continue;   // unrestricted; the worker may write anything not otherwise protected
            }
            List<String> refused = new ArrayList<>();
            for (String entry : writeSet) {
                PathPolicy.Verdict verdict = PathPolicy.check(entry, writeSet,
                    task.acceptanceTestDir(), null);
                if (!verdict.allowed()) {
                    refused.add(entry);
                }
            }
            if (refused.size() == writeSet.size()) {
                violations.add("task '" + task.title() + "' may write nothing: every path in its "
                    + "write set " + refused + " is protected from workers, so no worker could "
                    + "ever complete it. The acceptance tests are written for you before any task "
                    + "runs: drop this task if writing them was its job, or point its writeSet at "
                    + "the source directories its production code belongs in");
            }
        }
    }

    /**
     * A write set must point somewhere this repository's build actually compiles.
     *
     * <p>Found live on 2026-08-30 against a repository whose root {@code pom.xml} is a
     * {@code <packaging>pom</packaging>} aggregator over three modules, with no {@code src/} of its
     * own. The planner — which until that day was told nothing whatever about the repository's
     * shape — wrote write sets of {@code src/main/java/com/zeroz4j/bookstore/...}. Workers obeyed
     * them exactly, the compile stage passed on the three untouched modules, and the run delivered
     * six files nothing will ever compile. Two tasks were marked delivered.
     *
     * <p><b>The root case is a violation; an unknown module is a warning.</b> A write set rooted at
     * a repository root that compiles nothing is impossible in the same sense an acceptance-test
     * write set is: no arrangement of work can make it land in the build, so the graph is
     * regenerated. A write set under some OTHER directory that is not currently a module is only
     * warned about, because a plan may legitimately intend to create that module — rejecting it
     * would fail honest work, and the verification gate catches it later if the module never
     * appears.
     *
     * <p>A layout that could not be read produces neither: nothing is known, so nothing is claimed.
     */
    private static void checkWriteSetIsInTheBuild(TaskGraph graph, BuildLayout.Layout layout,
                                                  List<String> violations, List<String> warnings) {
        if (layout == null || !layout.determined()) {
            return;
        }
        boolean rootCompiles = layout.compilingModules().contains("");
        for (Task task : graph.tasks()) {
            Set<String> writeSet = task.writeSet();
            if (writeSet == null || writeSet.isEmpty()) {
                continue;
            }
            for (String raw : writeSet) {
                String entry = BuildLayout.normalize(raw);
                if (entry == null || entry.isEmpty() || insideBuild(entry, layout.sourceRoots())) {
                    continue;
                }
                String owner = srcTreeOwner(entry);
                if (owner == null) {
                    continue;   // not source-root shaped at all; the path policy owns that question
                }
                if (owner.isEmpty() && !rootCompiles) {
                    violations.add("task '" + task.title() + "' writes to " + entry
                        + ", which is not in this project's build: the repository root compiles "
                        + "nothing (its build file only lists modules), so nothing there is ever "
                        + "compiled or packaged. The directories the build does compile are "
                        + String.join(", ", layout.sourceRoots())
                        + " — put this task's writeSet under one of those");
                } else if (!layout.compilingModules().contains(owner)) {
                    warnings.add("task '" + task.title() + "' writes to " + entry + ", and "
                        + owner + " is not currently a module of this build — unless this task also "
                        + "creates it, nothing written there will ever be compiled");
                }
            }
        }
    }

    /** True when the entry is a source root, inside one, or a directory containing one. */
    private static boolean insideBuild(String entry, List<String> sourceRoots) {
        for (String root : sourceRoots) {
            if (root == null || root.isEmpty()) {
                continue;
            }
            if (entry.equals(root) || entry.startsWith(root + "/") || root.startsWith(entry + "/")) {
                return true;
            }
        }
        return false;
    }

    /** The directory owning the {@code src/} tree this entry sits in, or null if it is in none. */
    private static String srcTreeOwner(String entry) {
        String[] segments = entry.split("/");
        for (int i = 0; i < segments.length; i++) {
            if ("src".equals(segments[i])) {
                return String.join("/", List.of(segments).subList(0, i));
            }
        }
        return null;
    }

    /**
     * Just the coverage rule inside {@link #validate(TaskGraph, StoryScope, BuildLayout.Layout)}:
     * every criterion of the scope claimed by some task, and nothing claimed from outside it.
     * Exposed on its own for callers that re-check a STORED graph's shape independently of every
     * other PLAN-stage invariant — e.g. {@code EndToEndLoopTest}'s link 7, which exists to catch
     * exactly the class of bug {@code PlanLinksItsCriteriaTest} was written for: a graph that
     * passed this rule when the planner produced it, then had its links silently dropped by a
     * later copy step before it reached the store. Calling this rather than a second
     * implementation of it means the harness and the live PLAN gate can never disagree about what
     * "claimed" means.
     */
    public List<String> checkCoverage(TaskGraph graph, StoryScope scope) {
        List<String> violations = new ArrayList<>();
        if (scope != null && !scope.isEmpty()) {
            checkCriterionCoverage(graph, scope, violations);
        }
        return violations;
    }

    /**
     * Every task with no check of its own must be depended on, directly or transitively, by a
     * task that DOES have one — otherwise it is enabler work nothing in the plan is ever built
     * on top of. Structural, not scope-dependent: it reads only which tasks CLAIM a check
     * ({@link Task#criterionIds()}), never which checks belong to the story's slice.
     *
     * <p>Found live 2026-09-03 (run {@code ede2068b}'s bookshelf demo, four tasks): "Define Book
     * Data Model", "Define BookService Interface" and "Build Client UI for Rating" claimed no
     * check; only "Implement BookService on Server" did, and it was the wrong one to carry it —
     * "a rating can be assigned to a book" is what the reader SEES, and the client task that
     * delivers that was left claiming nothing, with nothing built on top of it either. Nothing
     * before this told a used enabler from a dead one, so the plan was accepted, a wave was spent
     * on it, and the harness's own test helper ({@code PlanTaskLinkageCheck}, which now delegates
     * here) was the only thing that ever caught it, after the fact.
     *
     * <p><b>Skipped when NOTHING in the graph claims anything.</b> That shape is
     * {@link #checkCriterionCoverage}'s own "not one of the N task(s)" case — a planner that
     * dropped every link at once — and repeating it here as N separate "nothing builds on this"
     * messages would be the same fault reported under a different name for every task in the
     * plan, which is exactly the noise {@link #checkCriterionCoverage}'s own special case exists
     * to avoid.
     *
     * <p>Exposed publicly, alongside {@link #checkCoverage}, so a caller that re-checks a STORED
     * graph's shape independently of every other PLAN-stage invariant — {@code
     * PlanTaskLinkageCheck} — reads "an enabler nothing builds on" through the one definition the
     * live PLAN gate itself uses, rather than a second implementation that could quietly drift
     * from it.
     */
    public List<String> checkEnablersAreUsed(TaskGraph graph) {
        return checkEnablersAreUsed(graph, null);
    }

    /**
     * The same rule, with every objection saying HOW to satisfy it.
     *
     * <p><b>Why the remedies are in the text</b> (harness run 40, 2026-09-26, DeepSeek V4 Flash,
     * Bookshelf demo, story "Books and ratings persist across restart"). Attempt 1 was rejected
     * because "Implement BookshelfServiceImpl delegating to BookshelfStore" claimed no check and
     * nothing depended on it; the story's one check sat on the BookshelfStore task, whose test
     * drives the store. The objection said what was wrong and nothing about what would be right.
     * Attempt 2 changed something else and lost the Book and Rating contracts; attempt 3 restored
     * them and brought the orphan back, word for word; the run parked. A model told only "this is
     * an enabler nothing builds on" has three honest ways out and no idea which one we accept.
     * Now the objection names them, with the facts the model needs to choose:
     *
     * <ol>
     *   <li><b>drop the task</b> — said together with who already claims each check, so the model
     *       can see that dropping loses nothing a check proves (or, when a check is still
     *       unclaimed, that this is not yet true);</li>
     *   <li><b>make a task that claims a check depend on it</b> — offered only for checked tasks
     *       this task does not itself already build on, because an edge back to one of those is a
     *       cycle (in run 40 the only checked task was the store the service delegates to, so this
     *       way out does not exist, and offering it would have invited the next rejection);</li>
     *   <li><b>move a check onto it</b>, when its own code is what that check proves.</li>
     * </ol>
     *
     * <p>The first sentence is unchanged, so every reader that matched on it — the harness's own
     * {@code PlanTaskLinkageCheck}, and an operator reading the park brief — still does.
     *
     * <p>If the model still cannot settle it, the last attempt's plan is not parked for this
     * alone: see {@link UnusedEnablers}.
     *
     * @param scope the story, for naming which task claims which check; null leaves the remedies
     *              generic but still present
     */
    public List<String> checkEnablersAreUsed(TaskGraph graph, StoryScope scope) {
        return checkEnablersAreUsed(graph, scope, null);
    }

    /**
     * The same, with the design — so the contracts a task writes by write set without naming them
     * count as what its code uses, exactly as {@link TypeDependencyOrder} reads them.
     *
     * <p>A task whose whole write set lies in browser-only modules is never objected to: see
     * {@link #unusedEnablers(TaskGraph, DesignDocument)}.
     */
    public List<String> checkEnablersAreUsed(TaskGraph graph, StoryScope scope,
                                             DesignDocument design) {
        List<String> violations = new ArrayList<>();
        List<Task> unused = unusedEnablers(graph, design);
        if (unused.isEmpty()) {
            return violations;
        }
        Map<UUID, Set<UUID>> downstream = transitiveClosure(graph);
        List<Task> checked = graph.tasks().stream()
            .filter(t -> t.criterionIds() != null && !t.criterionIds().isEmpty())
            .toList();
        String claimedBy = whoClaimsWhat(checked, scope);
        boolean everyCheckClaimed = everyCheckClaimed(checked, scope);
        for (Task task : unused) {
            // A checked task this one already builds on (directly or through others) cannot be made
            // to depend on it: that edge would close a cycle.
            List<String> canDependOnIt = checked.stream()
                .filter(c -> !downstream.getOrDefault(c.id(), Set.of()).contains(task.id()))
                .map(c -> "'" + c.title() + "'")
                .toList();
            StringBuilder sb = new StringBuilder("task '" + task.title() + "' claims no check, and "
                + "nothing that depends on it claims one either — it is an enabler nothing "
                + "builds on, so no check would ever prove its work. Fix it in ONE of these "
                + "ways: ");
            int n = 1;
            sb.append("(").append(n++).append(") drop the task");
            if (everyCheckClaimed) {
                sb.append(" — every check of this story is already claimed without it");
                if (!claimedBy.isEmpty()) {
                    sb.append(" (").append(claimedBy).append(")");
                }
                sb.append(", so nothing a check proves is lost");
            } else {
                sb.append(", if no check needs its code");
            }
            sb.append("; ");
            if (!canDependOnIt.isEmpty()) {
                sb.append("(").append(n++).append(") if a task that claims a check really needs "
                    + "this task's code, add an edge from '").append(task.title())
                    .append("' to that task (the tasks that claim checks and could wait for it: ")
                    .append(String.join(", ", canDependOnIt)).append("); ");
            }
            sb.append("(").append(n).append(") if this task's own code is what a check proves, "
                + "move that check's ref out of the criterionRefs of the task that claims it now "
                + "and into this task's, and make this task depend on every task whose types it "
                + "uses.");
            violations.add(sb.toString());
        }
        return violations;
    }

    /**
     * The tasks {@link #checkEnablersAreUsed} objects to: claiming no check, and depended on —
     * directly or transitively — by nothing that does. Empty when nothing in the graph claims
     * anything (see that method for why).
     */
    public List<Task> unusedEnablers(TaskGraph graph) {
        return unusedEnablers(graph, null);
    }

    /**
     * The same, except that a task whose whole write set lies in browser-only modules is never
     * one.
     *
     * <p>Harness run 49, 2026-09-30 (the Bookshelf demo on ZeroZ Stack / TeaVM, story "add, edit
     * and remove books"). The design named contracts for the client types
     * {@code BookshelfClient} and {@code BookshelfPage}. Attempts 1 and 3 planned the task that
     * writes them and were told "it is an enabler nothing builds on ... drop the task"; attempt 2
     * dropped it and was told "no task delivers the contract ...BookshelfClient". The two rules
     * contradicted each other and the run parked in PLAN. Neither could be satisfied: code that
     * runs only in a browser can never be proved by an acceptance test (a JUnit test on a plain
     * JVM may not call it, harness run 37), so a UI task is necessarily one that claims no check
     * and that no checked task builds on — yet it is the user-facing half of the story and must be
     * built. Its proof is that it compiles: the build verification every task already gets.
     * Such a task is therefore exempt here, and so from the last attempt's drop in {@link
     * UnusedEnablers}, which reads this same list. (Before this, only an edge no code needed was
     * refused — harness runs 44/45, 2026-09-27 — and the task itself was still an orphan.)
     *
     * <p>Every other enabler keeps the rule as it was: any path to a checked task counts, since a
     * JVM enabler's acceptance test may construct the concrete class it writes (harness run 38).
     */
    public List<Task> unusedEnablers(TaskGraph graph, DesignDocument design) {
        List<Task> unused = new ArrayList<>();
        if (graph == null || graph.tasks() == null) {
            return unused;
        }
        Map<UUID, Boolean> hasChecks = new HashMap<>();
        boolean anyClaimAtAll = false;
        for (Task task : graph.tasks()) {
            boolean has = task.criterionIds() != null && !task.criterionIds().isEmpty();
            hasChecks.put(task.id(), has);
            anyClaimAtAll |= has;
        }
        if (!anyClaimAtAll) {
            return unused;
        }
        Map<UUID, Set<UUID>> downstream = transitiveClosure(graph);
        for (Task task : graph.tasks()) {
            if (hasChecks.get(task.id())) {
                continue; // carries its own check(s) — not an enabler, nothing more to ask of it
            }
            if (writesOnlyBrowserOnlyCode(task)) {
                continue; // no acceptance test can ever prove it; compiling is its proof
            }
            boolean builtOn = downstream.getOrDefault(task.id(), Set.of()).stream()
                .anyMatch(id -> hasChecks.getOrDefault(id, false));
            if (!builtOn) {
                unused.add(task);
            }
        }
        return unused;
    }

    /**
     * True when the task writes something, and everything it writes (build files apart) lies in a
     * module {@link BrowserOnlyCode} found to be browser-only. False with no survey at all.
     */
    boolean writesOnlyBrowserOnlyCode(Task task) {
        if (!browserOnly.any() || task == null || task.writeSet() == null
                || task.writeSet().isEmpty()) {
            return false;
        }
        boolean any = false;
        for (String raw : task.writeSet()) {
            if (BuildFilesInTheJob.isBuildFile(raw)) {
                continue;
            }
            String entry = BuildLayout.normalize(raw);
            if (entry == null || browserOnlyModuleOf(entry) == null) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** The browser-only module whose directory holds this repo-relative path, or null. */
    private BrowserOnlyCode.Module browserOnlyModuleOf(String entry) {
        BrowserOnlyCode.Module best = null;
        for (BrowserOnlyCode.Module module : browserOnly.browserOnly()) {
            String dir = module.dir() == null ? "" : module.dir();
            boolean holds = dir.isEmpty() || entry.startsWith(dir + "/");
            if (holds && (best == null || dir.length() > best.dir().length())) {
                best = module;
            }
        }
        return best;
    }

    /** True when every check of the scope is claimed by one of {@code checked}; true unscoped. */
    static boolean everyCheckClaimed(List<Task> checked, StoryScope scope) {
        return scope == null || scope.isEmpty()
            || scope.criteria().stream().allMatch(c -> checked.stream()
                .anyMatch(t -> t.criterionIds() != null && t.criterionIds().contains(c.id())));
    }

    /** "R6:C1 by 'Implement BookshelfStore…'", one entry per claimed check of the scope. */
    static String whoClaimsWhat(List<Task> checked, StoryScope scope) {
        if (scope == null || scope.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (var criterion : scope.criteria()) {
            for (Task task : checked) {
                if (task.criterionIds() != null && task.criterionIds().contains(criterion.id())) {
                    String ref = scope.refFor(criterion.id());
                    parts.add((ref == null ? "a check" : ref) + " by '" + task.title() + "'");
                }
            }
        }
        return String.join(", ", parts);
    }

    /**
     * Every criterion of the slice claimed, and nothing claimed from outside it.
     *
     * <p><b>Partial coverage is a violation, not a warning</b>, and for the same reason as no
     * coverage at all: a criterion no task claims is one the test author is never asked to write a
     * test for, so it can only ever be UNKNOWN, so the story cannot legitimately reach REVIEW. A
     * warning would be advice nobody acts on inside a run that then delivers less than it promised
     * while reading as a success. The cost of being strict is one more planner call — the graph is
     * regenerated, and the single-task fallback behind that claims the whole slice by
     * construction, so there is always a way forward.
     *
     * <p>The wholly-unassigned case is called out separately because it is a different mistake with
     * a different fix. One missing ref is a planner that misjudged a task; NO refs anywhere is a
     * planner that ignored the instruction, or — as on 2026-08-30 — something downstream stripping
     * the links off a plan that was correct when it was written. Listing eight identical
     * "no task delivers …" lines hides which of those it was.
     */
    private static void checkCriterionCoverage(TaskGraph graph, StoryScope scope,
                                               List<String> violations) {
        Set<UUID> inScope = new HashSet<>();
        for (var criterion : scope.criteria()) {
            inScope.add(criterion.id());
        }
        Set<UUID> claimed = new HashSet<>();
        boolean anyClaimAtAll = false;
        for (Task task : graph.tasks()) {
            for (UUID id : task.criterionIds()) {
                anyClaimAtAll = true;
                if (inScope.contains(id)) {
                    claimed.add(id);
                } else {
                    violations.add("task '" + task.title() + "' claims a criterion outside the "
                        + "story's slice — keep in its criterionRefs only refs from this story's "
                        + "list (" + String.join(", ", scope.criterionRefs()) + ") and remove "
                        + "any other");
                }
            }
        }
        if (!anyClaimAtAll && !inScope.isEmpty()) {
            violations.add("not one of the " + graph.tasks().size() + " task(s) in this plan is "
                + "linked to any of the story's " + inScope.size() + " check(s) ("
                + String.join(", ", scope.criterionRefs()) + "), so no test would be written for "
                + "any of them and the story could never reach REVIEW. Put each ref in the "
                + "criterionRefs of the one task whose own work finishes what that check proves");
            return;
        }
        for (UUID id : inScope) {
            if (!claimed.contains(id)) {
                violations.add("no task delivers criterion " + scope.refFor(id)
                    + " — the story could never reach REVIEW. Put " + scope.refFor(id) + " in the "
                    + "criterionRefs of the one task whose own work finishes what it checks");
            }
        }
    }

    /**
     * Checks that name the same test class are claimed by the same task(s).
     *
     * <p>A task is verified against the tests it claims and only those (author decision,
     * 2026-09-02), and what is placed into a candidate's worktree is a FILE. A test class is one
     * file. So two checks in one class split across two tasks would put the whole file — both
     * methods — into both tasks' worktrees, and the earlier task would be failed by a test written
     * for work it was never asked to do. Worse, the test author writes per task, so the second
     * task's author silently overwrites the first task's file. Method-level selection is not
     * supported — it would have to be expressed in each toolchain's own selector syntax inside a
     * verification contract the operator owns — and this invariant is what makes it unnecessary.
     *
     * <p>A violation rather than a warning, like coverage, and for the same reason: the planner is
     * told the rule and regenerated, and the single-task fallback satisfies it by construction. A
     * check with no test reference, or one that cannot be read as a class, constrains nothing.
     */
    private static void checkOneTestClassOneTask(TaskGraph graph, StoryScope scope,
                                                 List<String> violations) {
        Map<UUID, Set<String>> claimersOf = new HashMap<>();
        for (Task task : graph.tasks()) {
            for (UUID id : task.criterionIds()) {
                claimersOf.computeIfAbsent(id, k -> new java.util.TreeSet<>()).add(task.title());
            }
        }
        // test class -> (claiming task set -> the refs claimed that way)
        Map<String, Map<Set<String>, List<String>>> byClass = new HashMap<>();
        for (var criterion : scope.criteria()) {
            Set<String> claimers = claimersOf.get(criterion.id());
            if (claimers == null || claimers.isEmpty()) {
                continue; // coverage reports an unclaimed check; nothing to say about its class
            }
            CriterionEvidence.TestRef ref = CriterionEvidence.TestRef.parse(criterion.testClassOrFile());
            if (ref == null || ref.className() == null || ref.className().isBlank()) {
                continue;
            }
            String simple = ref.className().substring(ref.className().lastIndexOf('.') + 1);
            byClass.computeIfAbsent(simple, k -> new HashMap<>())
                .computeIfAbsent(claimers, k -> new ArrayList<>())
                .add(scope.refFor(criterion.id()));
        }
        for (var entry : byClass.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            StringBuilder detail = new StringBuilder();
            for (var split : entry.getValue().entrySet()) {
                detail.append(detail.isEmpty() ? "" : "; ")
                    .append(String.join(", ", split.getValue()))
                    .append(" on task(s) ").append(split.getKey());
            }
            violations.add("the checks proved by test class " + entry.getKey()
                + " are split across tasks (" + detail + ") — a test class is one file, and a file "
                + "is verified with one task, so every check of " + entry.getKey()
                + " must be claimed by the same task");
        }
    }

    /**
     * A check is answered by exactly one task: the one that completes it. A task another task
     * depends on is an enabler, and answers for no check of its own.
     *
     * <p>Found live 2026-09-03 (run {@code ba04471f}): one agreed check (R4:C1, "a rating can be
     * assigned to a book") was split by the planner across three tasks — model, server, client —
     * because nothing told it not to. The test author writes one file per check, and a task is
     * verified against exactly the files it claims, so two of the three tasks answered for a check
     * with no test file of their own. The run parked at TEST_AUTHORING with a message that pointed
     * at a healthy endpoint.
     *
     * <p>Rather than reject a plan a reasonable planner might well produce, this NORMALISES it:
     * among the tasks claiming one check, the check stays on the task nothing else claiming it
     * depends on — the leaf of their dependency chain, since that is the task whose own candidate
     * actually delivers the behaviour the check proves — and is removed from the rest, which
     * become plain enablers. Several tied leaves (a diamond: two tasks both feeding one later task,
     * with nothing between them) are broken first by preferring the leaf whose write set touches a
     * UI/client module — a check is usually proved by what the user sees — else by whichever leaf
     * comes last in the plan's own task order.
     *
     * <p>Only when the claiming tasks do not all sit in ONE connected dependency chain — some pair
     * among them has no path between them in either direction, even by way of a third claiming task
     * — is this still a violation: two leaves with nothing that says which is more "finished" than
     * the other. The plan is sent back naming both, the same re-validate path every other PLAN
     * violation uses.
     */
    private static void normalizeOneCheckOneTask(TaskGraph graph, StoryScope scope,
                                                  List<String> violations, List<String> warnings) {
        Map<UUID, List<Task>> claimersOf = new LinkedHashMap<>();
        for (Task task : graph.tasks()) {
            for (UUID id : task.criterionIds()) {
                claimersOf.computeIfAbsent(id, k -> new ArrayList<>()).add(task);
            }
        }
        Map<UUID, Set<UUID>> reachable = null; // computed lazily; most plans need it for nothing
        for (var criterion : scope.criteria()) {
            List<Task> claimers = claimersOf.get(criterion.id());
            if (claimers == null || claimers.size() < 2) {
                continue; // the common case: one check, one task — nothing to normalise
            }
            if (reachable == null) {
                reachable = transitiveClosure(graph);
            }
            String refLabel = scope.refFor(criterion.id());
            if (refLabel == null) {
                refLabel = criterion.id().toString();
            }

            List<List<Task>> components = weaklyConnectedComponents(claimers, reachable);
            if (components.size() > 1) {
                Task a = components.get(0).get(0);
                Task b = components.get(1).get(0);
                violations.add("check " + refLabel + " is claimed by '" + a.title() + "' and '"
                    + b.title() + "', and no dependency relates them (directly or through another "
                    + "task that also claims it) — a check is answered by exactly one task, so the "
                    + "plan must make one of these depend on the other, or claim the check from "
                    + "only one of them");
                continue;
            }

            List<Task> leaves = leaves(claimers, reachable);
            Task winner;
            String why;
            if (leaves.size() == 1) {
                winner = leaves.get(0);
                why = "nothing else claiming it depends on this task";
            } else {
                Task uiPick = leaves.stream().filter(TaskGraphValidator::writesUiOrClientModule)
                    .findFirst().orElse(null);
                if (uiPick != null) {
                    winner = uiPick;
                    why = "tied with " + (leaves.size() - 1) + " other leaf task(s); kept the one "
                        + "whose write set touches a UI/client module";
                } else {
                    winner = leaves.get(leaves.size() - 1); // last of the tied leaves in plan order
                    why = "tied with " + (leaves.size() - 1) + " other leaf task(s); kept the one "
                        + "listed last in the plan";
                }
            }

            List<String> removedFrom = new ArrayList<>();
            for (Task task : claimers) {
                if (task == winner) {
                    continue;
                }
                Set<UUID> ids = new HashSet<>(task.criterionIds());
                ids.remove(criterion.id());
                task.setCriterionIds(ids);
                removedFrom.add("'" + task.title() + "'");
            }
            warnings.add("check " + refLabel + " was on " + claimers.size() + " task(s) ("
                + claimers.stream().map(t -> "'" + t.title() + "'").collect(Collectors.joining(", "))
                + "); kept on '" + winner.title() + "' (" + why + "), removed from "
                + String.join(", ", removedFrom));
        }
    }

    /** True when nothing among the OTHER claimers depends on this one — a terminal task. */
    private static List<Task> leaves(List<Task> claimers, Map<UUID, Set<UUID>> reachable) {
        List<Task> leaves = new ArrayList<>();
        for (Task candidate : claimers) {
            Set<UUID> downstream = reachable.getOrDefault(candidate.id(), Set.of());
            boolean dependedOnByAnother = claimers.stream()
                .anyMatch(other -> other != candidate && downstream.contains(other.id()));
            if (!dependedOnByAnother) {
                leaves.add(candidate);
            }
        }
        return leaves;
    }

    /**
     * The claimers of one check, grouped by weak connectivity: two are in the same group when one
     * reaches the other, directly or transitively — including by way of a third claimer, so a
     * two-parent diamond (A and B both feeding C, nothing directly between A and B) is ONE group.
     * More than one group here means some claimer has no dependency path to any other at all.
     */
    private static List<List<Task>> weaklyConnectedComponents(List<Task> claimers,
                                                               Map<UUID, Set<UUID>> reachable) {
        Map<UUID, UUID> parent = new LinkedHashMap<>();
        for (Task task : claimers) {
            parent.put(task.id(), task.id());
        }
        for (int i = 0; i < claimers.size(); i++) {
            for (int j = i + 1; j < claimers.size(); j++) {
                UUID a = claimers.get(i).id();
                UUID b = claimers.get(j).id();
                boolean related = reachable.getOrDefault(a, Set.of()).contains(b)
                    || reachable.getOrDefault(b, Set.of()).contains(a);
                if (related) {
                    union(parent, a, b);
                }
            }
        }
        Map<UUID, List<Task>> byRoot = new LinkedHashMap<>();
        for (Task task : claimers) {
            byRoot.computeIfAbsent(find(parent, task.id()), k -> new ArrayList<>()).add(task);
        }
        return new ArrayList<>(byRoot.values());
    }

    private static UUID find(Map<UUID, UUID> parent, UUID id) {
        UUID cursor = id;
        while (!parent.get(cursor).equals(cursor)) {
            cursor = parent.get(cursor);
        }
        return cursor;
    }

    private static void union(Map<UUID, UUID> parent, UUID a, UUID b) {
        UUID ra = find(parent, a);
        UUID rb = find(parent, b);
        if (!ra.equals(rb)) {
            parent.put(rb, ra);
        }
    }

    /** Heuristic: does this task's write set touch a directory that looks like a UI/client module? */
    private static boolean writesUiOrClientModule(Task task) {
        if (task.writeSet() == null) {
            return false;
        }
        for (String raw : task.writeSet()) {
            String normalized = raw.toLowerCase(java.util.Locale.ROOT).replace('\\', '/');
            for (String segment : normalized.split("/")) {
                if (segment.equals("client") || segment.equals("ui") || segment.equals("web")
                        || segment.equals("frontend") || segment.endsWith("-client")
                        || segment.endsWith("-ui") || segment.endsWith("-web")
                        || segment.endsWith("-frontend")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<TaskEdge> edges(TaskGraph graph) {
        return graph.dependencies() == null ? List.of() : graph.dependencies();
    }

    /**
     * The tasks Kahn's algorithm could not schedule — those on a cycle and those that wait on one.
     * Empty for an acyclic graph. Named in the cycle objection (harness run 40, 2026-09-26) so the
     * planner is told where the loop is, not just that there is one.
     */
    private static List<Task> tasksInOrBehindACycle(TaskGraph graph) {
        Map<UUID, List<UUID>> adjacency = new HashMap<>();
        Map<UUID, Integer> inDegree = new HashMap<>();
        for (Task task : graph.tasks()) {
            adjacency.put(task.id(), new ArrayList<>());
            inDegree.put(task.id(), 0);
        }
        for (TaskEdge edge : edges(graph)) {
            if (adjacency.containsKey(edge.from()) && inDegree.containsKey(edge.to())) {
                adjacency.get(edge.from()).add(edge.to());
                inDegree.merge(edge.to(), 1, Integer::sum);
            }
        }
        Deque<UUID> ready = new ArrayDeque<>();
        inDegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        Set<UUID> visited = new HashSet<>();
        while (!ready.isEmpty()) {
            UUID id = ready.poll();
            visited.add(id);
            for (UUID next : adjacency.get(id)) {
                if (inDegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        List<Task> stuck = new ArrayList<>();
        for (Task task : graph.tasks()) {
            if (!visited.contains(task.id())) {
                stuck.add(task);
            }
        }
        return stuck;
    }

    /**
     * Two tasks may share write-set paths only when one depends (transitively) on the other —
     * then they can never run concurrently. Paths overlap when equal or one is a prefix
     * directory of the other.
     */
    private static void checkWriteSetDisjointness(TaskGraph graph, List<String> violations) {
        Map<UUID, Set<UUID>> reachable = transitiveClosure(graph);
        List<Task> tasks = graph.tasks();
        for (int i = 0; i < tasks.size(); i++) {
            for (int j = i + 1; j < tasks.size(); j++) {
                Task a = tasks.get(i);
                Task b = tasks.get(j);
                boolean ordered = reachable.getOrDefault(a.id(), Set.of()).contains(b.id())
                    || reachable.getOrDefault(b.id(), Set.of()).contains(a.id());
                if (ordered) {
                    continue; // never concurrent
                }
                String overlap = firstOverlap(a.writeSet(), b.writeSet());
                if (overlap != null) {
                    violations.add("concurrently schedulable tasks '" + a.title() + "' and '"
                        + b.title() + "' overlap on write path: " + overlap + " — give that path "
                        + "to only one of them (name files rather than a shared directory), or, "
                        + "if one of them needs the other's work, add an edge between them so they "
                        + "run one after the other");
                }
            }
        }
    }

    private static Map<UUID, Set<UUID>> transitiveClosure(TaskGraph graph) {
        Map<UUID, List<UUID>> adjacency = new HashMap<>();
        for (Task task : graph.tasks()) {
            adjacency.put(task.id(), new ArrayList<>());
        }
        for (TaskEdge edge : edges(graph)) {
            List<UUID> next = adjacency.get(edge.from());
            if (next != null) {
                next.add(edge.to());
            }
        }
        Map<UUID, Set<UUID>> closure = new HashMap<>();
        for (UUID start : adjacency.keySet()) {
            Set<UUID> seen = new HashSet<>();
            Deque<UUID> stack = new ArrayDeque<>(adjacency.get(start));
            while (!stack.isEmpty()) {
                UUID node = stack.pop();
                if (seen.add(node)) {
                    stack.addAll(adjacency.getOrDefault(node, List.of()));
                }
            }
            closure.put(start, seen);
        }
        return closure;
    }

    /**
     * <p><b>Build files are excluded from this rule</b> (2026-09-03). Every task that may write a
     * module's sources now also has that module's build file in its write set, so two concurrent
     * tasks in one module share it by construction — and taking it away from one of them would
     * bring back exactly the defect that change removed: a task that cannot declare the dependency
     * its own code needs. What disjointness buys is conflict-free integration, and a dependency
     * declaration is an additive three-line block that usually merges; when it does not, the
     * integrator parks the run naming both candidates, which is what it does for any conflicting
     * merge. Sharing a source directory is still a violation, because two tasks writing the same
     * class is not a merge conflict, it is two plans.
     */
    private static String firstOverlap(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return null; // empty write set = unrestricted, checked separately once PLAN is real
        }
        for (String pathA : a) {
            if (BuildFilesInTheJob.isBuildFile(pathA)) {
                continue;
            }
            for (String pathB : b) {
                if (BuildFilesInTheJob.isBuildFile(pathB)) {
                    continue;
                }
                if (overlaps(pathA, pathB)) {
                    return pathA + " / " + pathB;
                }
            }
        }
        return null;
    }

    private static boolean overlaps(String rawA, String rawB) {
        String a = normalize(rawA);
        String b = normalize(rawB);
        return a.equals(b) || a.startsWith(b + "/") || b.startsWith(a + "/");
    }

    private static String normalize(String path) {
        String normalized = path.replace('\\', '/');
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
