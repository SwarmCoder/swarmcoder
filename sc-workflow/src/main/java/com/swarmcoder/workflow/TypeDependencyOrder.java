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
import com.swarmcoder.knowledge.ProjectTypes;
import com.swarmcoder.verify.TypeDeliverability;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A task whose code uses a type another task of the same plan writes must run after that task.
 * This class finds every such use, adds the missing dependency when the fact is mechanical, sends
 * the plan back when it is not, and tells each worker exactly where the types it uses live.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Harness run 39, 2026-09-25 (DeepSeek V4 Flash, the Bookshelf demo, story "Persist books and
 * ratings across browser restarts", run {@code d51ee25e}). The plan had four tasks. Task A, "Create
 * shared data model classes (Book and Rating)", wrote {@code .../bookshelf/shared/Book.java} and
 * {@code Rating.java} and depended on nothing. Task B, "Create shared BooksService interface", wrote
 * {@code .../shared/BooksService.java} and ALSO depended on nothing — yet the contract it was told
 * to deliver, word for word, was {@code BooksService{List<Book> getBooks(); List<Rating>
 * getRatings(); void saveBook(Book book); void saveRating(Rating rating); }}. Both were dispatched
 * in the same wave. Task B's workers started from a checkout with no {@code Book} and no {@code
 * Rating} in it, so nothing they wrote could compile ("cannot find symbol class Book"). One worker
 * went looking for the types in {@code ...bookshelf.model} — the package the knowledge brief's
 * worked example happened to use, which in this project holds only {@code Message.java} — asked
 * the help desk, got an answer after 17 turns, and was killed for making no progress after 34. No
 * worker could have succeeded: the code it was asked to write did not compile until task A's winner
 * had been merged, and nothing in the plan said to wait for it.
 *
 * <p>{@link TaskGraphValidator} checked everything about that plan it knew to check — acyclic,
 * disjoint write sets, every contract delivered exactly once, every check claimed — and every one
 * of those held. What it never asked was the one question the compiler asks first: does the code
 * this task writes name a type that does not exist yet?
 *
 * <h2>Why the missing dependency is ADDED rather than sent back</h2>
 *
 * <p>The fact is mechanical. The design's contracts name each type's members as Java ({@code
 * "List<Book> getBooks()"}); the plan's own {@code deliversContracts} and write sets say which task
 * writes {@code Book}. When the text of what task B must deliver names a type only task A writes,
 * "B cannot compile before A" is not a judgement a model has to make, and asking the planner to
 * make it again costs one of its three attempts and a model call to reach an answer that is
 * already known — with every chance of a new plan breaking something else. So the edge is added
 * with a line in the run log that says which types forced it. This is the same stance {@link
 * TaskGraphValidator#validate} already takes on one-check-one-task, which it normalises rather
 * than rejects.
 *
 * <h2>When it is NOT added, and why</h2>
 *
 * <ul>
 *   <li><b>The plan already orders them the other way</b> (B before A, directly or through
 *       another task). Adding A→B would make a cycle. With contract evidence this is a violation
 *       fed back to the planner: the plan's ordering contradicts its own types, and which half the
 *       planner got wrong is its call, not this class's.</li>
 *   <li><b>Each task's contract uses a type the other writes.</b> Two types that name each other
 *       cannot be compiled apart in either order. Also a violation: they belong in one task.</li>
 *   <li><b>The only evidence is the task's prose instructions</b>, not a contract's Java. Prose is
 *       weaker — a model writing "Book and Rating will later be used by BooksService" in task A's
 *       instructions does not mean A needs {@code BooksService}. So prose evidence adds an edge
 *       only when nothing points the other way: never against contract evidence, never when the two
 *       tasks' prose names each other's types (it cannot say which is the real direction), and
 *       never when it would close a cycle. Skipping is logged, never silent.
 *       <b>One case is no longer skipped (live run 90, 2026-10-07, section 66).</b> A task told
 *       to use {@code UtcDateTime} was planned BEFORE the task that creates it: the planner had
 *       written both its edges the wrong way round. The line "read as describing what a later
 *       task builds on its work" was logged, the order was kept, six workers had to create the
 *       type outside their write set and every candidate failed for it: 2,610,416 input and
 *       97,078 output tokens. That reading is now taken only when it can be true: the type
 *       already exists in the tree the run starts from (the task compiles against it as it is),
 *       or the later task in turn uses a NEW type the naming task creates (the planner's order
 *       has evidence of its own - run 40's shape). Otherwise the task names a type that will not
 *       be in its checkout and nothing says why the plan runs it first: the plan goes back with
 *       the pair and the edge to write. It is sent back and not turned round here because the
 *       planner said two things that cannot both hold - its edge and its instructions - and
 *       which one is wrong is not a mechanical fact.</li>
 *   <li><b>A read set that names a file another task creates</b> (the same run: the task's
 *       {@code readSet} was {@code .../UtcDateTime.java}) is the planner's own statement that
 *       the task reads that file, and a file that does not exist cannot be read. It counts like
 *       a contract: the edge is added when nothing orders the two, and the plan goes back when
 *       it orders them the other way.</li>
 *   <li><b>The contracts do not say enough.</b> A contract with no members and no signature
 *       sketch, and a task whose instructions never name the type, leave nothing to read. Nothing is
 *       invented: the plan is left as the planner wrote it. {@code
 *       ArchitectClient.CONTRACT_VOCABULARY_RULE} already requires members on every contract.</li>
 *   <li><b>A simple name is ambiguous</b> — two planned types share it, and neither is in the
 *       using task's own package. Resolving it would be a guess, so it is skipped and logged.</li>
 * </ul>
 *
 * <h2>How "who writes which type" is read</h2>
 *
 * <p>Two sources, both the plan's own promises: a task's {@link Task#deliveredContracts()} (a
 * contract naming a type), and a FILE entry in its write set, read into a fully-qualified name
 * exactly as {@link TypeDeliverability#typeNamed} reads it for the acceptance-test vocabulary
 * ({@link PlannedImplementations}, harness run 38) — source root + package path + {@code
 * SimpleName.java}. A directory entry names no single type and is not used.
 *
 * <h2>The worker's side of the same fact</h2>
 *
 * <p>Ordering fixes when task B runs; it does not tell B's worker where {@code Book} is. Run 39's
 * worker looked in the wrong package because nothing it was given named the right one — its
 * instructions listed only the types it must DELIVER. {@link #annotate} appends, to each task's
 * instructions, every type its code uses that an earlier task writes: the fully-qualified name (with
 * the contract's members when there is a contract), and the task that writes it.
 */
public final class TypeDependencyOrder {

    /** The heading of the block {@link #annotate} appends; also how a second call knows it ran. */
    static final String BRIEF_HEADING =
        "TYPES YOUR CODE USES THAT ANOTHER TASK OF THIS PLAN WRITES";

    /** A Java identifier, optionally dotted — a simple name, or a qualified one. */
    private static final Pattern NAME =
        Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    /**
     * What one pass decided.
     *
     * @param added      one line per dependency added, for the run log
     * @param violations reasons the plan must go back to the planner — a type use no ordering can
     *                   satisfy, or one the plan orders backwards
     * @param notes      uses that were deliberately left alone, and why, for the run log
     */
    public record Outcome(List<String> added, List<String> violations, List<String> notes) {}

    /** One task's code naming a type another task writes. */
    record Use(Task user, Task writer, String typeName, ApiContract contract, boolean fromContract,
               boolean fromReadSet, boolean newType) {

        /** A fact rather than wording: a contract's Java, or a read set naming a file to come. */
        boolean hard() {
            return fromContract || fromReadSet;
        }
    }

    private TypeDependencyOrder() {}

    /**
     * Adds to {@code graph} every dependency its tasks' own types require and it does not already
     * have. Mutates the graph's edge list in place (replacing it with a mutable copy), so what is
     * validated next, stored, and dispatched is the ordered plan.
     *
     * <p>A cyclic graph is left untouched: reachability means nothing there, and the validator's
     * own cycle violation already sends it back.
     *
     * @param design the design, for contracts a task writes by write set without naming them in
     *               {@code deliversContracts}; null is fine — the tasks' own contracts are read
     */
    public static Outcome apply(TaskGraph graph, DesignDocument design) {
        return apply(graph, design, null);
    }

    /**
     * The same, told which checkout the plan is layered onto, so that a type the checkout already
     * has is told apart from one that exists only once its task has run (live run 90).
     *
     * @param repoRoot the tree the run starts from; null when there is none, and then every
     *                 type a task of the plan writes is new
     */
    public static Outcome apply(TaskGraph graph, DesignDocument design, Path repoRoot) {
        List<String> added = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (graph == null || graph.tasks() == null || graph.tasks().size() < 2) {
            return new Outcome(added, violations, notes);
        }
        List<TaskEdge> edges = new ArrayList<>(graph.dependencies() == null
            ? List.of() : graph.dependencies());
        Map<UUID, Set<UUID>> reach = closure(graph.tasks(), edges);
        if (reach == null) {
            return new Outcome(added, violations, notes); // cyclic: the validator says so
        }

        List<Use> uses = uses(graph.tasks(), design, notes, repoRoot);
        // (writer, user) -> the uses behind that pair, contract evidence first in each list
        Map<List<UUID>, List<Use>> byPair = new LinkedHashMap<>();
        for (Use use : uses) {
            byPair.computeIfAbsent(List.of(use.writer().id(), use.user().id()),
                k -> new ArrayList<>()).add(use);
        }

        // Contract-backed pairs first: they are facts, and a prose pair must never pre-empt one
        // by taking the other direction first.
        List<List<UUID>> order = new ArrayList<>();
        byPair.forEach((pair, list) -> {
            if (list.stream().anyMatch(Use::hard)) {
                order.add(pair);
            }
        });
        byPair.keySet().stream().filter(p -> !order.contains(p)).forEach(order::add);

        boolean changed = false;
        Set<List<UUID>> reported = new HashSet<>();
        for (List<UUID> pair : order) {
            List<Use> list = byPair.get(pair);
            Use first = list.get(0);
            Task writer = first.writer();
            Task user = first.user();
            boolean contract = list.stream().anyMatch(Use::fromContract);
            String types = typesOf(list);
            if (reach.get(writer.id()).contains(user.id())) {
                continue; // already ordered, directly or through another task
            }
            List<Use> reverse = byPair.getOrDefault(List.of(user.id(), writer.id()), List.of());
            boolean reverseContract = reverse.stream().anyMatch(Use::fromContract);
            boolean readSet = list.stream().anyMatch(Use::fromReadSet);
            // What the wording names that is not in the start tree, each way (run 90).
            boolean namesNew = list.stream().anyMatch(Use::newType);
            boolean reverseNamesNew = reverse.stream().anyMatch(Use::newType);
            boolean wordingBothWays = !reverse.isEmpty() && !contract && !reverseContract
                && !readSet && reverse.stream().noneMatch(Use::fromReadSet);
            if (wordingBothWays && namesNew != reverseNamesNew && !namesNew) {
                // This task names a type that is already there; the other names one that is
                // not. Only the other can be in want of an order, and its own pair says so.
                continue;
            }
            // Wording both ways counts as "cannot say which way round" only when both sides
            // name a type to come, or neither does: wording about a type that already exists is
            // no evidence against a type that does not.
            boolean oneSided = wordingBothWays && namesNew && !reverseNamesNew;
            if (!reverse.isEmpty() && contract == reverseContract && !readSet && !oneSided) {
                if (!reported.add(canonical(pair))) {
                    continue;
                }
                String message = "task '" + user.title() + "' uses " + types + ", which task '"
                    + writer.title() + "' writes, and task '" + writer.title() + "' uses "
                    + typesOf(reverse) + ", which task '" + user.title() + "' writes";
                if (contract) {
                    violations.add(message + " — the two tasks' contracts name each other's "
                        + "types, so neither can compile before the other. Deliver those types in "
                        + "one task.");
                } else {
                    notes.add(message + " — both only in their instructions' wording, which "
                        + "cannot say which way round the real dependency runs; left as planned");
                }
                continue;
            }
            if (reach.get(user.id()).contains(writer.id())) {
                if (contract) {
                    violations.add("task '" + user.title() + "' uses " + types + " in the "
                        + "contract it delivers, and task '" + writer.title() + "' writes "
                        + (list.size() == 1 ? "it" : "them") + " — but the plan makes '"
                        + writer.title() + "' wait for '" + user.title() + "', so '"
                        + user.title() + "' would be written against types that do not exist "
                        + "yet. '" + user.title() + "' must depend on '" + writer.title()
                        + "', not the other way round.");
                } else if (readSet) {
                    boolean one = list.size() == 1;
                    violations.add("task '" + user.title() + "' has " + types + " in its read "
                        + "set, " + (one ? "a file that does" : "files that do") + " not exist "
                        + "until task '" + writer.title() + "' creates " + (one ? "it" : "them")
                        + " — but the plan makes '" + writer.title() + "' wait for '"
                        + user.title() + "'. " + howToOrder(writer, user));
                } else if (namesNew && !reverseNamesNew) {
                    // Live run 90: see the class javadoc. The type is not in the start tree, the
                    // task that creates it runs later, and nothing the later task uses of this
                    // one's says why.
                    String named = newTypesOf(list);
                    boolean one = !named.contains(",");
                    violations.add("task '" + user.title() + "' is told to use " + named
                        + ", which " + (one ? "does" : "do") + " not exist in the project yet "
                        + "and which task '" + writer.title() + "' creates — but the plan runs '"
                        + user.title() + "' BEFORE '" + writer.title() + "', so its workers "
                        + "would have to create " + (one ? "it" : "them") + " outside their "
                        + "write set and every candidate would fail (live run 90). "
                        + howToOrder(writer, user) + " If '" + user.title() + "' does not use "
                        + (one ? "it" : "them") + " after all, take the name out of its "
                        + "instructions instead.");
                } else {
                    // Harness run 40, 2026-09-26: "Create shared @DataModel classes Book, Rating
                    // and the BookshelfService interface" mentioned BookshelfStore in its prose,
                    // and "Implement BookshelfStore…" (which writes it) was correctly planned after
                    // it. Checked: nothing was added, nothing was sent back, and annotate() told no
                    // worker to import it — harmless. But the old wording ("…but the plan already
                    // has … after it; left as planned") read in the run log like a fault found on
                    // every attempt. An upstream task naming what a later task builds on its types
                    // is the ordinary shape of a plan, so the line now says that. It stays logged
                    // (skipping is never silent) because in the rarer case — prose that really does
                    // need the later type — this is the one place the reason is visible.
                    notes.add("task '" + user.title() + "' mentions " + types + " (written by '"
                        + writer.title() + "', which the plan runs after it) in its instructions "
                        + "— read as describing what a later task builds on its work, which is "
                        + "normal; the planner's order is kept");
                }
                continue;
            }
            edges.add(new TaskEdge(writer.id(), user.id()));
            changed = true;
            addReach(reach, writer.id(), user.id());
            added.add("task '" + user.title() + "' now depends on '" + writer.title() + "': "
                + (contract ? "the contract it delivers uses "
                    : readSet ? "its read set names " : "its instructions name ")
                + types + ", which '" + writer.title() + "' writes, and the plan had nothing "
                + "making it wait — its code could not have compiled until that task's work was "
                + "merged (harness run 39)");
        }
        if (changed) {
            graph.setDependencies(edges);
        }
        return new Outcome(added, violations, notes);
    }

    /**
     * Appends to each task's instructions the types its code uses that another task writes, with
     * their fully-qualified names and the task that writes each — see the class javadoc for the
     * worker in run 39 that looked for {@code Book} in the wrong package. Idempotent: a task
     * already carrying the block is left alone. Tasks that use nothing from another task are
     * untouched, so their instructions are byte-for-byte what the planner wrote.
     *
     * @return one log line per task that was told something
     */
    public static List<String> annotate(TaskGraph graph, DesignDocument design) {
        List<String> lines = new ArrayList<>();
        if (graph == null || graph.tasks() == null || graph.tasks().size() < 2) {
            return lines;
        }
        List<TaskEdge> edges = graph.dependencies() == null ? List.of() : graph.dependencies();
        Map<UUID, Set<UUID>> reach = closure(graph.tasks(), edges);
        if (reach == null) {
            return lines;
        }
        Map<UUID, List<Use>> byUser = new LinkedHashMap<>();
        for (Use use : uses(graph.tasks(), design, new ArrayList<>())) {
            // Only a type whose writer finishes before this task starts is in its checkout. A use
            // the ordering pass left alone (instructions' wording it could not be sure of) is not
            // listed: telling a worker to import a type its tree will not have is the fault this
            // block exists to prevent, not a hint.
            if (reach.get(use.writer().id()).contains(use.user().id())) {
                byUser.computeIfAbsent(use.user().id(), k -> new ArrayList<>()).add(use);
            }
        }
        for (List<Use> usesOfOne : byUser.values()) {
            Task user = usesOfOne.get(0).user();
            String instructions = user.instructions() == null ? "" : user.instructions();
            if (instructions.contains(BRIEF_HEADING)) {
                continue;
            }
            StringBuilder sb = new StringBuilder(instructions);
            sb.append("\n\n").append(BRIEF_HEADING).append(" — import each from exactly the "
                + "package given here. Do not create them, do not copy them, and do not look for "
                + "them in any other package:\n");
            Set<String> seen = new HashSet<>();
            for (Use use : usesOfOne) {
                if (!seen.add(use.typeName())) {
                    continue;
                }
                sb.append("  - ").append(use.contract() != null
                        ? use.contract().describe() : use.typeName())
                    .append(" — written by task '").append(use.writer().title()).append("'")
                    .append(", which finishes before yours starts, so it is already in your "
                        + "checkout")
                    .append('\n');
            }
            user.setInstructions(sb.toString());
            lines.add("told '" + user.title() + "' where the " + seen.size() + " type(s) it uses "
                + "from other tasks live: " + String.join(", ", new TreeSet<>(seen)));
        }
        return lines;
    }

    // --- reading the plan --------------------------------------------------------------------------

    /** Every (user, writer, type) the plan's own text shows, contract evidence first per user. */
    static List<Use> uses(List<Task> tasks, DesignDocument design, List<String> notes) {
        return uses(tasks, design, notes, null);
    }

    /**
     * @param repoRoot the tree the run starts from, to tell a type it already has from one that
     *                 exists only once its task has run; null: every planned type is new
     */
    static List<Use> uses(List<Task> tasks, DesignDocument design, List<String> notes,
                          Path repoRoot) {
        // fully-qualified type -> the tasks that write it, and the contract when there is one
        Map<String, Set<Task>> writers = new LinkedHashMap<>();
        Map<String, ApiContract> contractOf = new HashMap<>();
        Map<UUID, List<ApiContract>> contractsWritten = new LinkedHashMap<>();
        Map<UUID, Set<String>> typesWritten = new LinkedHashMap<>();
        for (Task task : tasks) {
            List<ApiContract> written = new ArrayList<>();
            Set<String> types = new LinkedHashSet<>();
            for (ApiContract contract : task.deliveredContracts()) {
                if (contract != null && contract.namesAType()) {
                    written.add(contract);
                    types.add(contract.typeName().strip());
                }
            }
            if (task.writeSet() != null) {
                for (String entry : task.writeSet()) {
                    String type = TypeDeliverability.typeNamed(entry);
                    if (type == null || types.contains(type)) {
                        continue;
                    }
                    types.add(type);
                    ApiContract designed = designContract(design, type);
                    if (designed != null) {
                        written.add(designed);
                    }
                }
            }
            for (String type : types) {
                writers.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(task);
            }
            for (ApiContract contract : written) {
                contractOf.putIfAbsent(contract.typeName().strip(), contract);
            }
            contractsWritten.put(task.id(), written);
            typesWritten.put(task.id(), types);
        }
        Map<String, Set<String>> bySimpleName = new HashMap<>();
        for (String type : writers.keySet()) {
            bySimpleName.computeIfAbsent(simpleName(type), k -> new TreeSet<>()).add(type);
        }

        StartTree existing = new StartTree(repoRoot, tasks);
        List<Use> uses = new ArrayList<>();
        for (Task user : tasks) {
            Set<String> own = typesWritten.get(user.id());
            Set<String> ownPackages = new HashSet<>();
            for (String type : own) {
                ownPackages.add(packageOf(type));
            }
            StringBuilder contractText = new StringBuilder();
            for (ApiContract contract : contractsWritten.get(user.id())) {
                for (String member : contract.members()) {
                    contractText.append(member == null ? "" : member).append('\n');
                }
                if (contract.signatureSketch() != null) {
                    contractText.append(contract.signatureSketch()).append('\n');
                }
            }
            Set<String> fromContract = typesNamed(contractText.toString(), own, ownPackages,
                bySimpleName, writers, user, notes);
            Set<String> fromProse = typesNamed(user.instructions(), own, ownPackages,
                bySimpleName, writers, user, notes);
            // A read set entry that is the file of a type another task creates (run 90): the
            // planner's own word that this task reads a file that is not there yet.
            Set<String> fromReadSet = new LinkedHashSet<>();
            if (user.readSet() != null) {
                for (String entry : user.readSet()) {
                    String type = TypeDeliverability.typeNamed(entry);
                    if (type != null && !own.contains(type) && writers.containsKey(type)
                            && existing.isNew(type)) {
                        fromReadSet.add(type);
                    }
                }
            }
            fromReadSet.removeAll(fromContract);
            fromProse.removeAll(fromContract);
            fromProse.removeAll(fromReadSet);
            addUses(uses, user, fromContract, true, false, writers, contractOf, existing);
            addUses(uses, user, fromReadSet, false, true, writers, contractOf, existing);
            addUses(uses, user, fromProse, false, false, writers, contractOf, existing);
        }
        return uses;
    }

    private static void addUses(List<Use> uses, Task user, Set<String> types, boolean fromContract,
                                boolean fromReadSet, Map<String, Set<Task>> writers,
                                Map<String, ApiContract> contractOf, StartTree existing) {
        for (String type : types) {
            Set<Task> by = writers.get(type);
            if (by == null || by.size() != 1) {
                continue; // two tasks writing one type is the validator's violation, not a use
            }
            Task writer = by.iterator().next();
            if (writer != user) {
                uses.add(new Use(user, writer, type, contractOf.get(type), fromContract,
                    fromReadSet, existing.isNew(type)));
            }
        }
    }

    /**
     * Which planned types the tree the run starts from already has. Read from the tree, never
     * from the plan's wording: a write-set file that is on disk, or a type the project's own
     * sources declare. With no tree every planned type is new.
     */
    private static final class StartTree {
        private final Path root;
        private final Set<String> filesThere = new HashSet<>();
        private ProjectTypes types;
        private boolean typesRead;

        StartTree(Path root, List<Task> tasks) {
            this.root = root;
            if (root == null) {
                return;
            }
            for (Task task : tasks) {
                for (String entry : task.writeSet() == null ? Set.<String>of() : task.writeSet()) {
                    String type = TypeDeliverability.typeNamed(entry);
                    try {
                        if (type != null && Files.isRegularFile(root.resolve(entry.strip()))) {
                            filesThere.add(type);
                        }
                    } catch (RuntimeException unreadable) {                 // noqa
                        // a path the file system will not take names no file that is there
                    }
                }
            }
        }

        boolean isNew(String type) {
            if (root == null) {
                return true;
            }
            if (filesThere.contains(type)) {
                return false;
            }
            if (!typesRead) {
                typesRead = true;
                try {
                    types = ProjectTypes.of(root);
                } catch (RuntimeException unreadable) {                     // noqa
                    types = null;
                }
            }
            return types == null || !types.declares(type);
        }
    }

    /**
     * The planned types other tasks write that {@code text} names — by fully-qualified name, or
     * by simple name when that resolves to exactly one planned type (preferring one in the
     * using task's own package, as Java itself would).
     */
    private static Set<String> typesNamed(String text, Set<String> own, Set<String> ownPackages,
                                          Map<String, Set<String>> bySimpleName,
                                          Map<String, Set<Task>> writers, Task user,
                                          List<String> notes) {
        Set<String> found = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return found;
        }
        Set<String> ownSimple = new HashSet<>();
        for (String type : own) {
            ownSimple.add(simpleName(type));
        }
        Matcher matcher = NAME.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (writers.containsKey(token)) {
                if (!own.contains(token)) {
                    found.add(token);
                }
                continue;
            }
            // In a dotted name only the first type-like segment can be a planned type named by
            // its simple name; what follows it is nested in it (java.util.Map.Entry is not the
            // planned Entry), and a package in front of it has to be that type's own.
            String[] segments = token.split("\\.");
            int firstType = 0;
            while (firstType < segments.length && (segments[firstType].isEmpty()
                    || !Character.isUpperCase(segments[firstType].charAt(0)))) {
                firstType++;
            }
            String qualifier = String.join(".",
                java.util.Arrays.copyOfRange(segments, 0, Math.min(firstType, segments.length)));
            boolean qualified = segments.length > 1 && !qualifier.isEmpty()
                && qualifier.indexOf('.') >= 0;
            for (int at = 0; at < segments.length; at++) {
                String segment = segments[at];
                if (segments.length > 1 && at != firstType) {
                    continue;
                }
                if (ownSimple.contains(segment)) {
                    continue; // the task's own type of that name shadows any other
                }
                Set<String> candidates = bySimpleName.get(segment);
                if (qualified && candidates != null) {
                    candidates = candidates.stream()
                        .filter(c -> packageOf(c).equals(qualifier))
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
                }
                if (candidates == null || candidates.isEmpty()) {
                    continue;
                }
                if (candidates.size() == 1) {
                    found.add(candidates.iterator().next());
                    continue;
                }
                List<String> samePackage = candidates.stream()
                    .filter(c -> ownPackages.contains(packageOf(c))).toList();
                if (samePackage.size() == 1) {
                    found.add(samePackage.get(0));
                } else {
                    String note = "task '" + user.title() + "' names " + segment + ", and "
                        + candidates.size() + " planned types have that name (" + candidates
                        + ") — not guessing which one it means";
                    if (!notes.contains(note)) {
                        notes.add(note);
                    }
                }
            }
        }
        return found;
    }

    private static ApiContract designContract(DesignDocument design, String type) {
        if (design == null || design.contracts() == null) {
            return null;
        }
        for (ApiContract contract : design.contracts()) {
            if (contract != null && contract.namesAType()
                    && type.equals(contract.typeName().strip())) {
                return contract;
            }
        }
        return null;
    }

    // --- small helpers -----------------------------------------------------------------------------

    /** The remedy every ordering objection ends with: which way round an edge is written. */
    private static String howToOrder(Task first, Task then) {
        return "An edge is written FROM the task that must finish first TO the task that waits "
            + "for it: make '" + then.title() + "' depend on '" + first.title() + "' with the "
            + "edge {\"from\": <id of '" + first.title() + "'>, \"to\": <id of '" + then.title()
            + "'>}, and remove the edge that runs the other way.";
    }

    /** The simple names of the uses whose type is not in the start tree. */
    private static String newTypesOf(List<Use> uses) {
        return typesOf(uses.stream().filter(Use::newType).toList());
    }

    private static String typesOf(List<Use> uses) {
        Set<String> names = new TreeSet<>();
        for (Use use : uses) {
            names.add(simpleName(use.typeName()));
        }
        return String.join(", ", names);
    }

    private static List<UUID> canonical(List<UUID> pair) {
        return pair.get(0).compareTo(pair.get(1)) <= 0 ? pair : List.of(pair.get(1), pair.get(0));
    }

    private static String simpleName(String type) {
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }

    private static String packageOf(String type) {
        int dot = type.lastIndexOf('.');
        return dot < 0 ? "" : type.substring(0, dot);
    }

    /** Each task's transitive dependents; null when the edges make a cycle. */
    private static Map<UUID, Set<UUID>> closure(List<Task> tasks, List<TaskEdge> edges) {
        Map<UUID, List<UUID>> next = new HashMap<>();
        for (Task task : tasks) {
            next.put(task.id(), new ArrayList<>());
        }
        for (TaskEdge edge : edges) {
            if (edge != null && next.containsKey(edge.from()) && next.containsKey(edge.to())) {
                next.get(edge.from()).add(edge.to());
            }
        }
        Map<UUID, Set<UUID>> reach = new HashMap<>();
        for (UUID start : next.keySet()) {
            Set<UUID> seen = new HashSet<>();
            Deque<UUID> stack = new ArrayDeque<>(next.get(start));
            while (!stack.isEmpty()) {
                UUID node = stack.pop();
                if (seen.add(node)) {
                    stack.addAll(next.get(node));
                }
            }
            if (seen.contains(start)) {
                return null;
            }
            reach.put(start, seen);
        }
        return reach;
    }

    /** Records a new edge from→to in an existing closure. */
    private static void addReach(Map<UUID, Set<UUID>> reach, UUID from, UUID to) {
        Set<UUID> gained = new HashSet<>(reach.get(to));
        gained.add(to);
        for (Map.Entry<UUID, Set<UUID>> entry : reach.entrySet()) {
            if (entry.getKey().equals(from) || entry.getValue().contains(from)) {
                entry.getValue().addAll(gained);
            }
        }
    }
}
