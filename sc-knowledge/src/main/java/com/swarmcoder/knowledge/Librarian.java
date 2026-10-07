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
package com.swarmcoder.knowledge;

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.InternalApi;
import com.swarmcoder.domain.KnowledgeBrief;
import com.swarmcoder.domain.LibraryDoc;
import com.swarmcoder.domain.Task;
import com.swarmcoder.syntax.SyntaxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.swarmcoder.domain.KnowledgeDoc;
import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.inference.VllmClient;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The Librarian (spec §13): front-loads knowledge before agents run. v2 (author decision
 * 2026-07-14) delivers conventions, codebase rules, best practices, and REAL source
 * examples via {@link KnowledgeCurator} — the tree-sitter signature dumps are gone:
 * a worker about to need an API sees the actual class source, curated docs, and a distilled
 * framework primer, so it does not invent APIs. External docs stay behind
 * {@link Context7Client}; the curator also backs the worker's {@code lookup_api} and the
 * chat analyst's research tools.
 */
public class Librarian {

    private static final Logger log = LoggerFactory.getLogger(Librarian.class);
    private static final int MAX_LIBRARIES = 12;

    /*
     * EVERY CHARACTER FIGURE BELOW IS THE FIGURE AT THE BASELINE ROOM (2026-09-25).
     *
     * Each was measured on the Qwen workers, whose room was 51,200 tokens, and each is now scaled by
     * MaterialBudget to the room of the model that reads it: the brief and the worker's lookup_api
     * answers by the workers' room (sizedFor), a lookup made for the architect or the expert by
     * that reader's own room (lookupApi(query, reader)). A worker at or below 51,200 tokens gets
     * exactly these numbers; the DeepSeek workers at 262,144 get 5.12 times them. The reasons
     * recorded on each constant are about what the channel is FOR and what it displaced inside a
     * fixed brief, and every one of those trades is kept, because every channel scales by the same
     * factor: the brief is the same share of the room it was, and each channel is the same share of
     * the brief. What does not scale are the COUNTS — MAX_LIBRARIES, SOURCE_FILES, MAX_NAMED_FILES —
     * and the shaping threshold MIN_SHAPED_CHARS: those were chosen for what a reader can use, not
     * for what fits.
     */
    private static final int MAX_BRIEF_CHARS = 18_000; // the ceiling, no longer the target

    /**
     * How the brief's characters are divided, and why the total no longer reaches the ceiling.
     *
     * <p><b>Measured, 2026-09-02, on {@code dev/bookshelf-demo} with {@code C:/work/zeroz4j} as
     * its reference folder.</b> The brief was 16,030 characters — 4,007 tokens, 7.8% of the
     * 51,200-token working context the Spark serves, and 70% of the whole shared prefix. It hit
     * the ceiling every time because the source channel was handed
     * {@code MAX_BRIEF_CHARS - rendered.length()}: whatever was left, always. A budget that is
     * "everything else" is not a budget.
     *
     * <p>Three things were being paid for and two of them were not worth it:
     *
     * <ul>
     *   <li><b>The reference README's opening, 623 tokens.</b> {@code conventions()} reads the
     *       head of each root's README, and the head of a README is its pitch — "a technical
     *       thesis", "Total Cost of Ownership", "AI Context Collapse". Every fact of substance in
     *       it (no JSON, no REST, Java compiled by TeaVM) was already in the worker's
     *       PROJECT_CONSTRAINTS twenty lines above, stated as a rule it must obey rather than a
     *       claim it may believe. Cut. The README is still in the document map by name and whole
     *       through {@code lookup_api}, and any section of it that answers the task can still be
     *       chosen by the slice below.</li>
     *   <li><b>The source channel returning markdown, 3,431 tokens.</b> It was called without
     *       {@code codeOnly}, so documents competed with code and won — for exactly the reason
     *       the {@code codeOnly} overload was written and applied to {@code lookup_api} and never
     *       here. What every worker actually received under the heading "real code, use these
     *       APIs", inside a {@code ```java} fence, was {@code docs/AGENT_PROMPTS.md} and
     *       {@code docs/contribute/documentation-plan.md}: a file of prompt templates and a plan
     *       for rewriting the documentation. Not one line of Java.</li>
     *   <li><b>The document map, 382 tokens for 45 documents by name.</b> Kept, untouched. It is
     *       the cheapest thing in the brief and it is what makes {@code lookup_api} worth calling
     *       instead of {@code javap}.</li>
     * </ul>
     *
     * <p>The source channel now gets a stated {@link #SOURCES_CHARS} and takes code only, so the
     * brief is as big as its content warrants and no bigger. {@link #MAX_BRIEF_CHARS} went back
     * to being what it says: a ceiling.
     */
    /**
     * The catalogue's budget. It doubled when the catalogue stopped being a list of file names and
     * became a list a worker can choose from: each line now carries the page's title and its first
     * sentence, guides first, and process material is not listed at all. 1,500 characters bought
     * nine of thirty-one pages; 3,000 buys about twenty, which is most of what a framework has,
     * and it is still the cheapest thing in the brief — this is the line that decides whether
     * lookup_api is called at all instead of javap.
     */
    private static final int DOC_MAP_CHARS = 3_000;

    /**
     * What the catalogue shrinks to when the brief also carries a worked example.
     *
     * <p>The catalogue exists to make {@code lookup_api} worth calling instead of {@code javap}.
     * When the brief already contains the code the task is to be shaped after, that argument is
     * mostly won, and the catalogue's job narrows to "there is documentation, here is roughly what
     * of, ask for it by topic". The pages themselves are unchanged and still reachable.
     */
    private static final int DOC_MAP_CHARS_BESIDE_AN_EXAMPLE = 900;

    /**
     * The worked example's budget, and what it is paid out of.
     *
     * <p><b>Measured, 2026-09-03/04.</b> Given the ZeroZ Stack task, prose documentation and a
     * search tool, this model read 43 documents and then disassembled the framework's jars —
     * three runs, 467 turns, zero files written. On a plain-Java task of the same shape it was
     * writing at turn 5 and green at turn 10. The difference is not reasoning and not context
     * size; it is whether there is code in this codebase's own idiom to be shaped after.
     *
     * <p>So this channel is worth more than the three it replaces, and it replaces them exactly:
     * the documentation SLICE (3,500 characters of the pages a query guessed at), the framework
     * PRIMER (3,000 characters of distilled prose), and the SPECULATIVE SOURCE channel (6,000
     * characters of files a query guessed at, which is the same idea done worse — chosen by words
     * rather than by shape, and with no reason given for why those files). 12,500 characters go
     * out, 12,000 come in, and the brief's ceiling does not move.
     *
     * <p>What stays: the exact library versions, the project's own curated rules, and the
     * documentation catalogue at a third of its size. None of those is a substitute for code and
     * none of them costs much.
     */
    private static final int WORKED_EXAMPLE_CHARS = 14_000;

    /**
     * What the worked example may never exceed, however large the reader's room.
     *
     * <p>The budget above grows with the workers' context, and on a 262,144-token worker it grew
     * to a 60,725-character example for a task that had to write one data class (harness run 58,
     * 2026-10-01). Room to read is not a reason to be handed a whole module: past a few files the
     * example stops being a shape to copy and becomes a codebase to study.
     */
    private static final int WORKED_EXAMPLE_CEILING = 16_000;

    /** One focused class or one focused test, chosen by what the work uses. */
    private static final int FOCUSED_EXAMPLE_CHARS = 8_000;
    private static final int DOC_SLICE_CHARS = 3_500;
    private static final int PRIMER_CHARS = 3_000;

    /**
     * The source channel's own budget — two files at the curator's 4,500-character read cap,
     * less the framing.
     *
     * <p>Deliberately smaller than the old open-ended share. This channel is SPECULATIVE: it
     * guesses from the task's title and write set which files the worker will want, before the
     * worker has looked at anything, and it pays for that guess in every worker's permanent
     * floor. {@code lookup_api} is the channel that answers the question the worker actually
     * has, it costs nothing until asked, and since the documentation search was fixed it
     * returns real content. Two well-chosen files preempt the API; six were paying rent.
     */
    private static final int SOURCES_CHARS = 6_000;

    /** How many files the speculative source channel may include. Was three. */
    private static final int SOURCE_FILES = 2;

    /** The project's own curated knowledge in the brief. It was an unnamed 5,000 in the call. */
    private static final int CURATED_CHARS = 5_000;

    /** The catalogue a genuine miss names, so the worker can ask again by topic. */
    private static final int MISS_MAP_CHARS = 1_200;

    private final Context7Client context7Client;
    private final DocsIndex docsIndex;
    private final KnowledgeCurator curator;
    /** Every Java file under the roots by name — how a question that names a type reaches it. */
    private final SourceIndex sources;
    /** ACTIVE curated knowledge (store-first KnowledgeDoc objects); nullable. */
    private final Supplier<List<KnowledgeDoc>> knowledgeDocs;
    /**
     * The workers' room — what the brief and a worker's {@code lookup_api} answers are sized by.
     * The baseline until a caller that knows the worker models says otherwise ({@link #sizedFor}).
     */
    private volatile MaterialBudget workerRoom = MaterialBudget.BASELINE;

    /** The project's standing rules as text; see {@link #setProjectRules}. Nullable. */
    private volatile Supplier<String> projectRules;

    /**
     * Wires in the project's standing rules, so an example can be chosen by the types they name.
     *
     * <p>A rule such as "a test obtains its service from the framework's test server" names the
     * one type every acceptance test must use, and "a service reaches its data through the
     * injected node" names the one every implementation must. Neither appears in a contract.
     * Unwired, examples are chosen from the task's own words alone.
     */
    public void setProjectRules(Supplier<String> rules) {
        this.projectRules = rules;
    }

    private String rulesText() {
        Supplier<String> rules = projectRules;
        if (rules == null) {
            return "";
        }
        try {
            String text = rules.get();
            return text == null ? "" : text;
        } catch (RuntimeException e) {
            return ""; // never the reason a brief cannot be assembled
        }
    }

    public Librarian(Context7Client context7Client, DocsIndex docsIndex, SyntaxService unusedSyntax) {
        this(context7Client, docsIndex, unusedSyntax, List.of());
    }

    public Librarian(Context7Client context7Client, DocsIndex docsIndex, SyntaxService unusedSyntax,
                     List<Path> contextRoots) {
        this(context7Client, docsIndex, contextRoots, null, null, null);
    }

    /**
     * Full wiring: {@code projectRoot} joins the curator's roots (for its curated docs),
     * {@code primerModel} enables the one-time framework-primer distillation (cached under
     * {@code primerCacheDir}). All nullable — everything degrades, nothing fails.
     */
    public Librarian(Context7Client context7Client, DocsIndex docsIndex, List<Path> contextRoots,
                     Path projectRoot, VllmClient primerModel,
                     Path primerCacheDir) {
        this(context7Client, docsIndex, contextRoots, projectRoot, primerModel, primerCacheDir, null);
    }

    public Librarian(Context7Client context7Client, DocsIndex docsIndex, List<Path> contextRoots,
                     Path projectRoot, VllmClient primerModel,
                     Path primerCacheDir,
                     Supplier<List<KnowledgeDoc>> knowledgeDocs) {
        this(context7Client, docsIndex, contextRoots, projectRoot, primerModel, primerCacheDir,
            knowledgeDocs, null, List.of());
    }

    /**
     * @param fileKinds the project's own {@code kind: path-fragment} lines, from the store — how
     *                  an operator says that this project's {@code /handbook/} is reference
     *                  material or its {@code /sandbox/} is not. Nullable; the defaults in
     *                  {@link FileKinds} cover an ordinary Maven or Gradle tree.
     */
    public Librarian(Context7Client context7Client, DocsIndex docsIndex, List<Path> contextRoots,
                     Path projectRoot, VllmClient primerModel,
                     Path primerCacheDir,
                     Supplier<List<KnowledgeDoc>> knowledgeDocs,
                     List<String> fileKinds,
                     List<String> contextUrls) {
        this.context7Client = context7Client;
        this.docsIndex = docsIndex;
        this.knowledgeDocs = knowledgeDocs;
        List<KnowledgeCurator.Root> roots = new ArrayList<>();
        if (projectRoot != null) {
            roots.add(new KnowledgeCurator.Root("project", projectRoot));
        }
        for (Path contextRoot : contextRoots == null ? List.<Path>of() : contextRoots) {
            if (contextRoot != null) {
                String label = contextRoot.getFileName() == null
                    ? contextRoot.toString() : contextRoot.getFileName().toString();
                roots.add(new KnowledgeCurator.Root(label, contextRoot));
            }
        }
        Path primers = primerCacheDir != null ? primerCacheDir
            : Paths.get(System.getProperty("user.home"), ".swarmcoder", "primers");
        // The reference index is persistent and shared across runs and restarts, so it belongs
        // beside the other caches rather than in a temporary folder. A caller that supplies its
        // own primer cache (every test does) gets the index inside it, so nothing leaks into the
        // operator's home directory.
        Path indexRoot = primerCacheDir != null ? primerCacheDir.resolve("refs")
            : Paths.get(System.getProperty("user.home"), ".swarmcoder", "rag", "refs");
        this.curator = new KnowledgeCurator(roots, primerModel, primers, indexRoot, fileKinds,
            contextUrls);
        this.sources = new SourceIndex(roots);
    }

    /** Built on first use; see {@link #libraryTypes()}. */
    private volatile LibraryTypes libraryTypes;

    /**
     * The types the reference checkouts really declare — every knowledge root except the
     * project's own — read once per process, since a reference checkout is read-only and does not
     * move within a run. What DESIGN_REVIEW holds a design's contracts to, so a contract cannot
     * promise a member of a library type that does not exist (harness runs 44/45, 2026-09-27: a
     * contract named {@code com.zeroz4j.ui.ListView}, which ZeroZ Stack has never had). Covers
     * nothing when no reference root is configured; never throws.
     */
    public LibraryTypes libraryTypes() {
        LibraryTypes cached = libraryTypes;
        if (cached == null) {
            synchronized (this) {
                if (libraryTypes == null) {
                    List<Path> references = new ArrayList<>();
                    for (KnowledgeCurator.Root root : curator.roots()) {
                        if (root != null && root.path() != null && !"project".equals(root.label())) {
                            references.add(root.path());
                        }
                    }
                    try {
                        // A type with no reference checkout is looked for in the library jars,
                        // through the Java language server when one is installed.
                        libraryTypes = LibraryTypes.of(references).withJarMembers(
                            name -> curator.languageServer().memberNames(name));
                    } catch (RuntimeException e) {
                        log.warn("could not index the reference checkouts' types ({}); contracts "
                            + "are not checked against them", e.toString());
                        libraryTypes = LibraryTypes.NONE;
                    }
                }
                cached = libraryTypes;
            }
        }
        return cached;
    }

    /** The curator — confined read/list/search over the knowledge roots (analyst tools). */
    public KnowledgeCurator curator() {
        return curator;
    }

    /**
     * Sizes the brief and every worker's {@code lookup_api} answer for the room the workers really
     * have (see {@link MaterialBudget}).
     *
     * <p>A setter, not a constructor argument, for the reason {@code ExpertDesk#sharedAcrossRun} is
     * one: the Librarian has five constructors called by thirty-odd tests and three wirings, and
     * only the places that know the worker models — {@code ProjectContext}, from the resolved
     * worker profiles, and a harness, from what it discovered at its endpoint — have anything to
     * say here. Never called means the baseline, which is exactly the brief this class always built.
     *
     * @param room the SMALLEST working room of the worker models that will read the brief, because
     *             one brief is shared by every worker of a task; null means the baseline
     */
    public Librarian sizedFor(MaterialBudget room) {
        this.workerRoom = room == null ? MaterialBudget.BASELINE : room;
        return this;
    }

    /** The workers' room this Librarian sizes its brief and its lookups by. */
    public MaterialBudget workerRoom() {
        return workerRoom;
    }

    /**
     * Whether the documentation server answered when we last tried, so the miss message can tell
     * the worker "asked, nothing there" apart from "never connected" — and so a run is warned
     * ONCE at startup instead of silently degrading (§32).
     */
    public boolean documentationServerReachable() {
        return context7Client != null && context7Client.connect();
    }

    /**
     * Whether a documentation-server key is configured at all — asked BEFORE anything is called,
     * because it decides whether {@code library_docs} is in the expert's tool list.
     *
     * <p>Not {@link #documentationServerReachable()}: that one connects, which costs a round trip
     * and can only be answered after the fact. A tool the model can see and call, that then always
     * answers "no key configured", is worse than no tool — it is a turn spent learning something
     * the tool list could have said for free.
     */
    public boolean documentationServerConfigured() {
        return context7Client != null && context7Client.hasApiKey();
    }

    /**
     * Builds the task's brief: exact library versions, curated conventions, the framework
     * primer, and FULL trimmed sources of the files most relevant to this task (the
     * API-preemption channel). Never fails the pipeline — empty sections instead.
     */
    /** Libraries whose Context7 docs pre-warm was already attempted this process. */
    private final Set<String> prewarmed = ConcurrentHashMap.newKeySet();

    public KnowledgeBrief assembleBrief(Path repoRoot, Task task) {
        return assembleBrief(repoRoot, task, null, null);
    }

    public KnowledgeBrief assembleBrief(Path repoRoot, Task task, OfflineLibraryBrief offline) {
        return assembleBrief(repoRoot, task, offline, null);
    }

    /**
     * What a change request is, for the one channel that needs the request's own words rather than
     * the architect's paraphrase of them.
     *
     * <p>A task's title and instructions are what the architect wrote after reading the report. The
     * report itself is where the reproduction lives — the input, the wrong output, the stack trace,
     * the type names — and {@link ChangeNeighbourhood} is built to read exactly that. So the run's
     * goal travels here rather than being re-derived from the plan.
     *
     * @param text           the change request as the operator gave it, verbatim
     * @param neighbourhoodChars the ceiling on the rendered neighbourhood. 0 or less means
     *                       {@link ChangeNeighbourhood#MAX_CHARS}; a caller that has discovered
     *                       what the endpoint actually serves passes
     *                       {@link ChangeNeighbourhood#forWorkingContext}, because 6,000 characters
     *                       is a bigger share of a small window than design §2.2 budgeted for
     */
    public record ChangeRequest(String text, int neighbourhoodChars) {

        /** The request at the full budget — right whenever nothing has measured the endpoint. */
        public static ChangeRequest of(String text) {
            return new ChangeRequest(text, 0);
        }

        int budget() {
            return neighbourhoodChars > 0 ? neighbourhoodChars : ChangeNeighbourhood.MAX_CHARS;
        }

        boolean hasText() {
            return text != null && !text.isBlank();
        }
    }

    /**
     * @param offline what this build could DECLARE but does not yet — see
     *                {@link OfflineLibraryBrief}. Null for every caller with no local Maven
     *                repository to consult, and the brief is then byte-for-byte what it was, so no
     *                such project's prefill cache goes cold and no worker is offered a library
     *                nobody has checked is on the disk the sandbox mounts.
     * @param change  the change request this run is delivering, or null for a greenfield build —
     *                and null makes the brief byte-for-byte what it was, for the same reason
     */
    public KnowledgeBrief assembleBrief(Path repoRoot, Task task, OfflineLibraryBrief offline,
                                        ChangeRequest change) {
        List<LibraryDoc> libraries = new ArrayList<>();
        StringBuilder rendered = new StringBuilder();
        // Read once, so one brief is sized by one room even if a caller resizes mid-run.
        MaterialBudget room = workerRoom;
        try {
            List<LibraryDoc> all = ManifestParser.parse(repoRoot);
            libraries.addAll(all.subList(0, Math.min(all.size(), MAX_LIBRARIES)));
            prewarmDocs(libraries);
            if (!libraries.isEmpty()) {
                rendered.append("### Available libraries (exact versions — use these APIs, do not invent)\n");
                for (LibraryDoc library : libraries) {
                    rendered.append("- ").append(library.coordinate());
                    if (library.version() != null && !library.version().isBlank()) {
                        rendered.append(' ').append(library.version());
                    }
                    rendered.append('\n');
                }
            }
            // The second list: what the build does NOT declare and a worker may add itself. It
            // follows the first because the two answer one question in order — what is here, then
            // what I may bring in — and a worker that reads only the first still reads a true one.
            if (offline != null) {
                rendered.append(offline.render());
            }
            String curated = renderKnowledgeDocs(room.chars(CURATED_CHARS));
            if (!curated.isBlank()) {
                rendered.append("\n### Conventions, rules & best practices (curated — follow these)\n")
                        .append(curated);
            }
            String query = task.title() + " " + (task.instructions() == null ? "" : task.instructions())
                + " " + String.join(" ", task.writeSet() == null ? Set.<String>of() : task.writeSet());

            // The worked example is chosen FIRST, because every other channel is sized around
            // whether there is one. See WORKED_EXAMPLE_CHARS for what it displaces and why.
            String worked = workedExample(repoRoot, task,
                Math.min(room.chars(WORKED_EXAMPLE_CHARS), WORKED_EXAMPLE_CEILING), change);
            boolean haveExample = !worked.isBlank();

            // The two documentation channels. The MAP is the catalogue — names and titles only,
            // cheap enough to always afford, and the thing that makes lookup_api worth calling
            // instead of reaching for javap. The SLICE is the handful of sections this task is
            // actually about. Bodies beyond that are on demand, not in the prefix.
            String map = curator.documentationMap(
                room.chars(haveExample ? DOC_MAP_CHARS_BESIDE_AN_EXAMPLE : DOC_MAP_CHARS));
            if (!map.isBlank()) {
                rendered.append("\n### Reference documentation you can read (call lookup_api with a "
                    + "topic — it searches ALL of these; do NOT decompile jars to find an API)\n")
                        .append(referenceVersions())
                        .append(versionMismatch(libraries))
                        .append(map);
            }
            // referenceOnly: a changelog's "Added" section mentions every subject the framework
            // has and so outscores every focused section against a real task description. The
            // whole slice was one trimmed chunk of CHANGELOG.md before this flag existed.
            String docs = haveExample ? "" : curator.relevantDocs(query, 4,
                room.chars(DOC_SLICE_CHARS), true);
            if (!docs.isBlank()) {
                rendered.append("\n### Documentation relevant to this task (authoritative — follow it)\n")
                        .append(docs);
            }

            String primer = haveExample ? "" : curator.primer(room.chars(PRIMER_CHARS));
            if (!primer.isBlank()) {
                rendered.append(primer);
            }
            // The neighbourhood of the change, immediately above the code it is about. Empty for
            // every greenfield build, so nothing existing moves by a character.
            rendered.append(neighbourhoodSection(change));

            // codeOnly, and a stated budget. Without the flag, documents outscored code and this
            // heading introduced two markdown files inside a ```java fence; without the budget
            // this channel took every character the brief had left, every time.
            if (haveExample) {
                rendered.append(worked);
            } else {
                String sources = curator.relevantSources(query, SOURCE_FILES,
                    room.chars(SOURCES_CHARS), true);
                if (!sources.isBlank()) {
                    rendered.append("\n### Reference sources relevant to this task (read-only "
                        + "— real code, use these APIs)\n").append(sources);
                }
            }
        } catch (Exception e) {
            log.warn("Librarian brief assembly failed for task '{}': {}", task.title(), e.getMessage());
        }
        // One hard ceiling on the whole brief. It sits in the shared prompt prefix, so an
        // unbounded brief is an unbounded prefill paid N times over.
        int maxBrief = room.chars(MAX_BRIEF_CHARS);
        String text = rendered.length() <= maxBrief ? rendered.toString()
            : rendered.substring(0, maxBrief) + "\n… (knowledge brief truncated)\n";
        return new KnowledgeBrief(UUID.randomUUID(), 1L, task.id(), libraries,
            List.<InternalApi>of(), text);
    }

    /**
     * The heading the neighbourhood is rendered under in a worker's brief.
     *
     * <p>Public so a harness can prove the text really reached a dispatched worker rather than
     * being a section somebody assembled and dropped.
     */
    public static final String NEIGHBOURHOOD_HEADING =
        "### The code this change is about, read out of this repository (file and line are real)";

    /**
     * The neighbourhood of a change against this codebase — the types the report names, where each
     * is declared, what touches them, and the test classes that already assert this behaviour.
     *
     * <p>Read here rather than passed in because the two things it needs are both already here: the
     * structural index over the project root, which {@link KnowledgeCurator} builds and caches, and
     * the request's own words. It <b>fails open everywhere</b> — an index that could not parse this
     * target, a report naming nothing this repository declares, an exception of any kind — and the
     * caller gets an empty answer carrying the reason, never a failure.
     *
     * @param change the change request, or null; null gives {@link ChangeNeighbourhood#none}
     */
    public ChangeNeighbourhood.Neighbourhood neighbourhoodOf(ChangeRequest change) {
        if (change == null || !change.hasText()) {
            return ChangeNeighbourhood.none("this run is not delivering a change request");
        }
        try {
            // No test source roots stated: the curator has no BuildLayout and sc-knowledge does not
            // depend on sc-verify. ChangeNeighbourhood falls back to the conventional test
            // directories for exactly this caller, which covers every Maven and Gradle tree.
            return ChangeNeighbourhood.read(change.text(), curator.semanticIndex(), List.of(),
                change.budget());
        } catch (Exception e) {
            log.warn("could not read the neighbourhood of this change: {}", e.getMessage());
            return ChangeNeighbourhood.none("the neighbourhood could not be read: "
                + e.getMessage());
        }
    }

    /** The neighbourhood as a brief section, or "" when there is nothing worth a heading. */
    private String neighbourhoodSection(ChangeRequest change) {
        if (change == null || !change.hasText()) {
            return "";
        }
        ChangeNeighbourhood.Neighbourhood neighbourhood = neighbourhoodOf(change);
        if (neighbourhood.isEmpty() || neighbourhood.brief().isBlank()) {
            // No heading without content under it, for the reason TaskBrief.render gives: a
            // heading promising "everything below was read out of your repository" above nothing
            // is a worse brief than no section at all.
            log.info("no neighbourhood for this change: {}", neighbourhood.note());
            return "";
        }
        log.info("neighbourhood channel: {}", neighbourhood.describe());
        return "\n" + NEIGHBOURHOOD_HEADING + "\n\n" + neighbourhood.brief().strip()
            + "\n\nThose declarations were read out of the repository you are working in, so those "
            + "types and members exist exactly as shown. The test classes named as already covering "
            + "this area are where this project asserts this behaviour today — read one before you "
            + "write anything, and make your change look like the code it exercises.\n";
    }

    /**
     * The nearest existing code for this task, in full: what resembles the types it must deliver,
     * or — when it delivers none, or what resembled them uses none of the library types the task
     * names — the one file that uses what the task uses.
     *
     * <p><b>Why there are two ways in (harness run 58, 2026-10-01).</b> This used to return ""
     * without a word for any task with no delivered contract. The implementation class behind a
     * contracted interface is not itself a contract — no acceptance test touches it — so the one
     * hard task of that plan got no example, and its workers spent eighty minutes asking how the
     * framework's injected database is used. {@link ExamplesByUse} serves that task.
     *
     * <p>Empty — and every channel it displaces comes back — only when neither way found
     * anything, and then the log says what was searched for.
     */
    private String workedExample(Path repoRoot, Task task, int budgetChars, ChangeRequest change) {
        List<ApiContract> contracts = task.deliveredContracts();
        Set<String> writeSet = task.writeSet() == null ? Set.<String>of() : task.writeSet();
        if (change == null && createsNoFile(repoRoot, writeSet)) {
            log.info("no worked example for task '{}': every file it may write already exists, "
                + "so it changes code and builds no new type; the code it changes is its example",
                task.title());
            return CHANGES_EXISTING_CODE;
        }
        try {
            Set<String> words = TaskBrief.taskWords(task.title(), task.instructions());
            WorkedExamples.Selection selection = contracts == null || contracts.isEmpty()
                ? null : byShape(contracts, words, change == null);
            boolean haveShape = selection != null && !selection.isEmpty();

            ExamplesByUse.Result byUse = ExamplesByUse.find(curator.shapes(),
                new ExamplesByUse.Query(textOf(task, contracts), rulesText(), 0.5,
                    namesWritten(task, contracts), simpleNames(contracts), false),
                Math.min(budgetChars, FOCUSED_EXAMPLE_CHARS));
            ExamplesByUse.Found focused = byUse.best();

            // On a new build, a shape match that uses none of the library types the task names is
            // a look-alike: a data class shaped like the one wanted, without the annotation that
            // makes the framework see it. The file that does use them is the better teacher. On a
            // change to existing code the shape match is the code being changed, and stays.
            if (haveShape && focused != null && change == null && !focused.used().isEmpty()
                && !usesAny(selection, focused.used())) {
                log.info("worked example for '{}': {} resembles the contract(s) but uses none of {}; "
                    + "showing the file that does instead", task.title(),
                    selection.projectLabel(), focused.used());
                haveShape = false;
            }
            if (haveShape) {
                // The task's own write set travels in, for one line of provenance: on a change to
                // code that already exists the nearest example is very often the file being
                // changed, and "copy this shape elsewhere" is the wrong instruction about it
                // (design §2.3).
                String rendered = TaskBrief.render(repoRoot, selection,
                    TaskBrief.Ingredients.examplesOnly(), budgetChars, writeSet);
                if (!rendered.isBlank()) {
                    // Which checkout this code is from, and at what version, stated where the code
                    // is rather than left to be derived. Measured: handed a working example from a
                    // reference folder, the model spent 66 shell commands disassembling the
                    // project's own jars to find out whether the example's API was true for the
                    // version it builds against.
                    rendered = rendered.replaceFirst("\n\n", "\n" + Matcher.quoteReplacement(
                        sourceOfTheExample(selection.matches().get(0).example().rootLabel()))
                        + "\n");
                    log.info("worked example for '{}': {} ({} matched contract(s), {} character(s))",
                        task.title(), selection.projectLabel(), selection.matches().size(),
                        rendered.length());
                    return rendered;
                }
            }
            if (focused != null) {
                String rendered = TaskBrief.renderFocused(repoRoot, focused.example(),
                    focused.why(), sourceOfTheExample(focused.example().rootLabel()));
                log.info("worked example for '{}': {} from {} — {} ({} character(s))",
                    task.title(), focused.example().relative(), focused.example().rootLabel(),
                    focused.why(), rendered.length());
                return rendered;
            }
            log.info("no worked example for task '{}': {} delivered contract(s) resembled nothing "
                + "worth showing, and nothing used what the task names — searched {}",
                task.title(), contracts == null ? 0 : contracts.size(), byUse.searched());
            return "";
        } catch (Exception e) {
            log.warn("worked-example selection failed for task '{}': {}", task.title(),
                e.getMessage());
            return "";
        }
    }

    /**
     * What stands in a brief where the worked example would be, for a task that creates no file.
     *
     * <p>Live run 90, 2026-10-07 (section 66). "Use UtcDateTime in add/edit contact screens"
     * might write two screens the project already had. Its brief carried "the nearest working
     * example, in full" of how to BUILD such a screen: four files of a chat example from the
     * reference material, 13,586 characters, 71% of the brief, sent again on each of 110 worker
     * calls. A task that creates nothing is not shown how to create something: the code it
     * changes is in its own checkout, one {@code body_of} away, and that is the example.
     *
     * <p>It is not blank on purpose. A blank answer means "no example was found" and brings
     * back the documentation slice, the primer and the reference sources the example displaces
     * - more pasted text, for a task that needs less.
     */
    static final String CHANGES_EXISTING_CODE = "\n### The code this task changes is its own "
        + "example\nEvery file this task may write is already in your checkout, so no example "
        + "of how to build one is pasted here. body_of <Type>, or body_of <Type>#<member>, "
        + "returns the code you are changing. When you want to see how something is written in "
        + "this project or its library, call find_example with the types you are working with: "
        + "it returns one whole file of real code that uses them.\n";

    /**
     * True when every entry of the write set is a file the tree already holds: read from the
     * tree, never from the task's wording. A folder, a file that is not there yet, an empty
     * write set (unrestricted) or a tree that cannot be read all answer false, and the task is
     * then shown an example as before.
     */
    static boolean createsNoFile(Path repoRoot, Set<String> writeSet) {
        if (repoRoot == null || writeSet == null || writeSet.isEmpty()) {
            return false;
        }
        try {
            for (String entry : writeSet) {
                if (entry == null || entry.isBlank()
                        || !Files.isRegularFile(repoRoot.resolve(entry.strip()))) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException unreadable) {                             // noqa
            return false;
        }
    }

    /**
     * The shape selection, from the reference folders alone when they hold an answer.
     *
     * <p>On a new build the project's own tree is the scaffold and whatever earlier tasks wrote —
     * code that is itself still being guessed at. Run 58 showed a data-class task the project's
     * own server module as "how this codebase does this". So the reference material is asked
     * first and the whole set, project included, only when it has nothing. A change to existing
     * code is the opposite case and is not narrowed: there the project IS the example.
     */
    private WorkedExamples.Selection byShape(List<ApiContract> contracts, Set<String> words,
                                             boolean newBuild) {
        List<WorkedExamples.Shape> all = curator.shapes();
        if (newBuild) {
            List<WorkedExamples.Shape> reference = new ArrayList<>();
            for (WorkedExamples.Shape shape : all) {
                if (!"project".equals(shape.rootLabel())) {
                    reference.add(shape);
                }
            }
            if (!reference.isEmpty() && reference.size() < all.size()) {
                WorkedExamples.Selection fromReference = WorkedExamples.select(reference,
                    contracts, words, curator.semanticIndex());
                if (!fromReference.isEmpty()) {
                    return fromReference;
                }
            }
        }
        // The structural index when there is one, shape similarity when there is not. Which of the
        // two happened is on the log line WorkedExamples writes, every time.
        return WorkedExamples.select(all, contracts, words, curator.semanticIndex());
    }

    private static boolean usesAny(WorkedExamples.Selection selection, List<String> names) {
        for (WorkedExamples.Match match : selection.matches()) {
            Set<String> used = ExamplesByUse.usedBy(match.example(), new java.util.HashSet<>());
            for (String name : names) {
                if (used.contains(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Everything a task says about itself that can name a library type. */
    private static String textOf(Task task, List<ApiContract> contracts) {
        StringBuilder text = new StringBuilder();
        text.append(task.title() == null ? "" : task.title()).append('\n')
            .append(task.instructions() == null ? "" : task.instructions()).append('\n');
        appendContracts(text, contracts);
        return text.toString();
    }

    private static void appendContracts(StringBuilder text, List<ApiContract> contracts) {
        for (ApiContract contract : contracts == null ? List.<ApiContract>of() : contracts) {
            if (contract == null) {
                continue;
            }
            text.append(contract.description() == null ? "" : contract.description()).append(' ')
                .append(contract.signatureSketch() == null ? "" : contract.signatureSketch())
                .append(' ').append(String.join(" ", contract.members())).append('\n');
        }
    }

    /** The class names this task writes: its contracts' and its write set's {@code .java} files. */
    private static List<String> namesWritten(Task task, List<ApiContract> contracts) {
        List<String> names = new ArrayList<>(simpleNames(contracts));
        for (String entry : task.writeSet() == null ? Set.<String>of() : task.writeSet()) {
            String path = entry.replace('\\', '/');
            if (path.endsWith(".java")) {
                names.add(path.substring(path.lastIndexOf('/') + 1, path.length() - 5));
            }
        }
        return names;
    }

    private static List<String> simpleNames(List<ApiContract> contracts) {
        List<String> names = new ArrayList<>();
        for (ApiContract contract : contracts == null ? List.<ApiContract>of() : contracts) {
            if (contract != null && contract.namesAType()) {
                names.add(contract.simpleTypeName());
            }
        }
        return names;
    }

    /**
     * A real test from the reference material, for whoever writes or repairs this task's
     * acceptance test — or "" when there is none, and the log says what was searched for.
     *
     * <p><b>Why (harness runs 56 to 60).</b> The test author was shown the contracts and the
     * rules and no code. Four of eight failed runs were its test: an API guessed by reflection, a
     * service constructed by hand twice, a builder method that does not exist. Every one of those
     * is answered by one test that already does it properly. Tests that use the types the
     * project's rules name — the test harness above all — are preferred, then tests of the same
     * kind of class as the one under test.
     *
     * @param designContracts the design's contracts; the ones this task delivers or names are the
     *                        code under test
     */
    public String testExample(Task task, List<ApiContract> designContracts) {
        if (task == null) {
            return "";
        }
        try {
            List<ApiContract> underTest = new ArrayList<>(task.deliveredContracts());
            String said = (task.title() == null ? "" : task.title()) + " "
                + (task.instructions() == null ? "" : task.instructions());
            for (ApiContract contract : designContracts == null ? List.<ApiContract>of()
                    : designContracts) {
                if (contract != null && contract.namesAType() && !underTest.contains(contract)
                    && said.contains(contract.simpleTypeName())) {
                    underTest.add(contract);
                }
            }
            List<String> hints = new ArrayList<>(namesWritten(task, underTest));
            hints.add("Test");
            return focusedTest(textOf(task, underTest), hints, simpleNames(designContracts),
                FOCUSED_EXAMPLE_CHARS, "the test author of '" + task.title() + "'");
        } catch (Exception e) {
            log.warn("test-example selection failed for task '{}': {}", task.title(), e.getMessage());
            return "";
        }
    }

    /**
     * One whole file of real code that uses what {@code what} names, picked on demand - the same
     * by-use selection that puts a worked example in a worker's brief and a test in the test
     * author's first prompt, asked for by a role in the middle of its work (2026-10-02), with the
     * library and project types it has by then decided it needs.
     *
     * @param what     the types involved and a few words on what the code must do
     * @param wantTest true for a test that does it, false for an implementation
     * @return the example, or a sentence beginning "Nothing in this project's material" that says
     *         what was searched
     */
    public String findExample(String what, boolean wantTest, String forWhom) {
        String text = what == null ? "" : what.strip();
        if (text.isEmpty()) {
            return "Nothing in this project's material can be searched for an empty description: "
                + "name the types involved.";
        }
        try {
            List<String> hints = new ArrayList<>();
            java.util.regex.Matcher names =
                java.util.regex.Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b").matcher(text);
            while (names.find()) {
                if (!hints.contains(names.group())) {
                    hints.add(names.group());
                }
            }
            if (wantTest) {
                hints.add("Test");
            }
            ExamplesByUse.Result result = ExamplesByUse.find(curator.shapes(),
                new ExamplesByUse.Query(text, rulesText(), 1.0, hints, List.of(), wantTest),
                FOCUSED_EXAMPLE_CHARS);
            ExamplesByUse.Found found = result.best();
            if (found == null) {
                log.info("no example of {} for {} — searched {}",
                    wantTest ? "a test" : "an implementation", forWhom, result.searched());
                return "Nothing in this project's material is " + (wantTest ? "a test" : "code")
                    + " that uses what you named - searched " + result.searched() + ". Name the "
                    + "library types themselves (the class names), or read a file you know with "
                    + "read_file.";
            }
            log.info("example of {} for {}: {} from {} — {}",
                wantTest ? "a test" : "an implementation", forWhom, found.example().relative(),
                found.example().rootLabel(), found.why());
            String source = sourceOfTheExample(found.example().rootLabel());
            String rendered = wantTest ? TaskBrief.renderTest(found.example(), found.why(), source)
                : TaskBrief.renderFocused(null, found.example(), found.why(), source);
            return rendered.isBlank() ? "Nothing in this project's material could be shown for "
                + "that: the file chosen, " + found.example().relative() + ", could not be read."
                : rendered;
        } catch (Exception e) {
            log.warn("example selection failed for {}: {}", forWhom, e.getMessage());
            return "Nothing in this project's material could be searched: " + e.getMessage();
        }
    }

    private String focusedTest(String text, List<String> hints, List<String> own, int maxChars,
                               String forWhom) {
        ExamplesByUse.Result result = ExamplesByUse.find(curator.shapes(),
            new ExamplesByUse.Query(text, rulesText(), 1.0, hints, own, true), maxChars);
        ExamplesByUse.Found found = result.best();
        if (found == null) {
            log.info("no worked example of a test for {} — searched {}", forWhom, result.searched());
            return "";
        }
        log.info("worked example of a test for {}: {} from {} — {} ({} byte(s))", forWhom,
            found.example().relative(), found.example().rootLabel(), found.why(),
            found.example().bytes());
        return TaskBrief.renderTest(found.example(), found.why(),
            sourceOfTheExample(found.example().rootLabel()));
    }

    /**
     * What the planner is shown before it writes task instructions: the one implementation and the
     * one test in the reference material that use what this design uses. The same selection the
     * workers and the test author get per task, asked once for the design as a whole.
     */
    public String planExamples(List<ApiContract> designContracts, String goal, int maxChars) {
        try {
            int each = Math.max(1_500, Math.min(maxChars, 2 * FOCUSED_EXAMPLE_CHARS) / 2);
            StringBuilder text = new StringBuilder(goal == null ? "" : goal).append('\n');
            appendContracts(text, designContracts);
            List<String> names = simpleNames(designContracts);
            List<String> implNames = new ArrayList<>();
            for (String name : names) {
                implNames.add(name + "Impl");
            }
            StringBuilder sb = new StringBuilder();
            ExamplesByUse.Result code = ExamplesByUse.find(curator.shapes(),
                new ExamplesByUse.Query(text.toString(), rulesText(), 1.0, implNames, names, false),
                each);
            if (code.best() != null) {
                sb.append(TaskBrief.renderFocused(null, code.best().example(), code.best().why(),
                    sourceOfTheExample(code.best().example().rootLabel())));
                log.info("worked example for the plan: {} — {}", code.best().example().relative(),
                    code.best().why());
            } else {
                log.info("no worked example for the plan — searched {}", code.searched());
            }
            List<String> testHints = new ArrayList<>(implNames);
            testHints.add("Test");
            sb.append(focusedTest(text.toString(), testHints, names, each, "the plan"));
            return sb.toString();
        } catch (Exception e) {
            log.warn("plan-example selection failed: {}", e.getMessage());
            return "";
        }
    }

    /** Where the worked example is from and at what version, beside the project's own. */
    private String sourceOfTheExample(String label) {
        if (label == null || "project".equals(label)) {
            // The example is this repository's own code, so there are no two versions to differ.
            // The sentence below exists because a worker handed code from somebody else's checkout
            // reasonably wondered whether its API was true for the version it builds against, and
            // spent 66 shell commands disassembling jars to find out. That doubt cannot arise here,
            // and offering it would invent one.
            return "";
        }
        for (KnowledgeCurator.Root root : curator.roots()) {
            if (root.label().equals(label)) {
                return "(from `" + label + "` at " + root.identity().describe()
                    + ". If anything below does not resolve against the versions your own build "
                    + "uses, write your best attempt and let the compiler say so — that is faster "
                    + "and more certain than reading the artefacts.)";
            }
        }
        return "";
    }

    /**
     * One line per reference folder saying WHICH version of it these documents describe.
     *
     * <p>A reference folder is somebody's live checkout, and it moves. Without this the worker is
     * reading documentation of unknown vintage and has no way to notice.
     *
     * <p>Public (test-only seam, widened visibility only) so a harness that wires this same
     * Librarian for its workers can report the identical version line in its own setup ledger,
     * instead of re-deriving it from {@code curator.roots()} and risking the two texts drifting
     * apart.
     */
    public String referenceVersions() {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeCurator.Root root : curator.roots()) {
            if ("project".equals(root.label())) {
                continue;
            }
            sb.append("(").append(root.label()).append(" docs at ")
              .append(root.identity().describe()).append(")\n");
        }
        return sb.toString();
    }

    /** Version mismatches already named this process, so the log says it once and not per task. */
    private final Set<String> mismatchesLogged = ConcurrentHashMap.newKeySet();

    /**
     * One line when the project depends on a different version of the framework than the reference
     * folder holds.
     *
     * <p>This is the failure that produces confidently wrong code: the documentation is real, the
     * example compiles in the folder it came from, and the method it uses does not exist in the
     * version the project builds against. Saying so costs a line and lets the worker prefer what
     * compiles.
     */
    private String versionMismatch(List<LibraryDoc> libraries) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeCurator.Root root : curator.roots()) {
            if ("project".equals(root.label())) {
                continue;
            }
            String group = KnowledgeCurator.groupIdOf(root);
            if (group.isBlank()) {
                continue;
            }
            String used = "";
            for (LibraryDoc library : libraries) {
                String coordinate = library.coordinate() == null ? "" : library.coordinate();
                if (coordinate.startsWith(group + ":") && library.version() != null
                    && !library.version().isBlank() && !library.version().contains("${")) {
                    used = library.version();
                    break;
                }
            }
            String folder = numericVersion(root.version());
            String project = numericVersion(used);
            if (folder.isEmpty() || project.isEmpty() || folder.equals(project)) {
                continue;
            }
            String line = "Your project uses " + used + " of " + group + "; these documents are "
                + "for " + root.version() + ". Where they disagree, prefer what compiles.\n";
            sb.append(line);
            if (mismatchesLogged.add(root.label() + "|" + used + "|" + root.version())) {
                log.warn("Reference folder '{}' is at {} but the project depends on {} of {} — "
                    + "documentation and dependencies disagree", root.label(), root.version(),
                    used, group);
            }
        }
        return sb.toString();
    }

    /** The {@code major.minor[.patch]} inside a version string, or "" when there is none. */
    static String numericVersion(String version) {
        if (version == null) {
            return "";
        }
        Matcher matcher = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(version);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** The chat analyst's grounding block (curated knowledge + primer + browsable inventory). */
    public String contextApiReference(int maxChars) {
        String curated = renderKnowledgeDocs(maxChars / 3);
        String rest = curator.analystContext(maxChars - curated.length());
        return curated.isBlank() ? rest
            : "\n## Curated knowledge (follow these)\n" + curated + rest;
    }

    /** ACTIVE knowledge docs rendered deterministically (store-first, no files). */
    private String renderKnowledgeDocs(int maxChars) {
        if (knowledgeDocs == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try {
            for (KnowledgeDoc doc : knowledgeDocs.get()) {
                if (!"ACTIVE".equals(doc.status()) || doc.body() == null) {
                    continue;
                }
                String block = "\n#### " + (doc.title() == null ? doc.slug() : doc.title())
                    + "\n" + doc.body().strip() + "\n";
                if (sb.length() + block.length() > maxChars) {
                    sb.append("… (curated knowledge truncated)\n");
                    break;
                }
                sb.append(block);
            }
        } catch (Exception e) {
            log.warn("Knowledge doc rendering failed: {}", e.getMessage());
        }
        return sb.toString();
    }

    /**
     * Fires one background Context7 fetch per manifest library (once per process) so the
     * local docs index is hot before any worker calls {@code lookup_api}. Best-effort:
     * with the MCP server down this is a cheap no-op per library.
     */
    private void prewarmDocs(List<LibraryDoc> libraries) {
        for (LibraryDoc library : libraries) {
            String name = library.coordinate() == null ? null
                : library.coordinate().substring(library.coordinate().indexOf(':') + 1);
            if (name == null || name.isBlank() || !prewarmed.add(name)) {
                continue;
            }
            Thread fetcher = new Thread(() -> fetchLibraryDocs(name), "docs-prewarm-" + name);
            fetcher.setDaemon(true);
            fetcher.start();
        }
    }

    /** External docs path — Context7-backed, pre-warming the local index (spec §13). */
    public Optional<String> fetchLibraryDocs(String libraryName) {
        return fetchLibraryDocs(libraryName, libraryName);
    }

    /** @param query what is actually being asked — the server ranks and scopes its answer by it */
    public Optional<String> fetchLibraryDocs(String libraryName, String query) {
        Optional<String> libId = context7Client.resolveLibrary(libraryName, query);
        if (libId.isPresent()) {
            Optional<String> content = context7Client.fetchDocs(libId.get(), query);
            content.ifPresent(c -> {
                if (docsIndex != null) {
                    docsIndex.indexDocs(libId.get(), c);
                }
                // And into the ONE index, split into sections and tagged with the library, so it
                // competes with the reference folders' pages instead of being a fallback that is
                // only consulted once they have failed.
                curator.indexLibraryDocs(libId.get(), libraryVersion(libId.get()), c);
            });
            return content;
        }
        return Optional.empty();
    }

    /**
     * The version a library id names. Context7 ids carry it as a trailing segment
     * ({@code /vaadin/flow/v24.4.0}); an id without one is whatever the server serves as current,
     * and saying "latest" is honest about that.
     */
    static String libraryVersion(String libraryId) {
        if (libraryId == null) {
            return "latest";
        }
        Matcher matcher = Pattern.compile("/v?(\\d+(?:\\.\\d+)*)$").matcher(libraryId);
        return matcher.find() ? matcher.group(1) : "latest";
    }

    /**
     * The worker's {@code lookup_api(query)} backend (spec §4.8). Order matters and it changed
     * (§32): the reference folders' OWN documentation is asked first, because that is the
     * material a worker is about to reverse-engineer a jar for. Then the pre-warmed Context7
     * index, then real sources, then a live Context7 fetch.
     *
     * <p>This half is not bound by the prompt-prefix budget — it is a tool result, per worker,
     * per question — so it can afford whole documentation sections where the brief cannot.
     * Never throws.
     *
     * <p><b>Prose and code together, not prose INSTEAD OF code.</b> Documentation used to return
     * early: if any section scored at all, the answer was prose and the source channel was never
     * consulted. Since some section always scores against a multi-word question, that made the
     * reference folders' Java files — 681 of them in the folder this was measured on — unreachable
     * in practice. Measured, against the real run that produced the stall guard: the worker asked
     * for "client view obtain service instance ... example code" and got a page about which ports
     * the examples listen on, while the source channel, had it been reached, held {@code TodoView}
     * and {@code PaymentsDeskView} — two client views doing exactly what it was trying to write.
     * It went to {@code javap} instead and burned 105,529 tokens. Prose says what a thing is for;
     * a real file says what to type. A worker about to write code needs both, so it now gets both,
     * with the budget split between them.
     *
     * <p><b>And the code is shaped to the question</b> ({@link SourceShaper}): a type name gets
     * the type's public surface, a member name gets that member with its body, a path gets the
     * file. The first 2,400 characters of a file — a license header and the imports — answered
     * nothing and cost every worker hundreds of tokens per question.
     */
    public String lookupApi(String query) {
        return lookupApi(query, Set.of(), Set.of());
    }

    /**
     * As {@link #lookupApi(String)}, but never hands this worker a documentation section it has
     * already been given, and re-ranks toward the words THIS question added since its earlier
     * ones (§32 extended — the 2026-09-04 incident: {@code lookup_api} answered three different
     * questions with the same leading section and only warned about it).
     *
     * @param alreadyGivenSections every section (by {@link KnowledgeCurator#sectionKey}) this
     *                             worker has already been shown this task
     * @param newTerms             words in {@code query} the worker did not use in any earlier
     *                             question — the ones a refined question actually added
     */
    public String lookupApi(String query, Set<String> alreadyGivenSections, Set<String> newTerms) {
        return lookupApi(query, alreadyGivenSections, newTerms, workerRoom);
    }

    /**
     * A lookup sized for a reader that is not a worker — the architect's research, the expert's
     * {@code lookup_docs} — by that reader's own room.
     *
     * <p>Without it those readers were handed an answer sized for the WORKERS and then cut it at
     * their own cap: in the DeepSeek workers' room a lookup answer may run to 30,720 characters,
     * the prose half first, and an architect on the generic 32,768-token cloud shape keeps the
     * first 6,000 of it — documentation only, with the real code half cut off. Sized by the
     * reader, the answer is split between prose and code at the reader's own size, as it always
     * was.
     *
     * @param reader the room of the model that will read the answer; null is the baseline
     */
    public String lookupApi(String query, MaterialBudget reader) {
        return lookupApi(query, Set.of(), Set.of(),
            reader == null ? MaterialBudget.BASELINE : reader);
    }

    /**
     * The one lookup every overload above lands in — package-visible since harness run 37
     * (2026-09-25) so the expert's {@code lookup_docs} can carry its own session's "already given"
     * sections the way a worker's {@code lookup_api} always has (see {@code ExpertTools}).
     */
    String lookupApi(String query, Set<String> alreadyGivenSections, Set<String> newTerms,
                     MaterialBudget reader) {
        int lookupChars = reader.chars(LOOKUP_CHARS);
        int docChars = reader.chars(DOC_CHARS);
        try {
            Optional<String> file = fileByPath(query, lookupChars);
            if (file.isPresent()) {
                return truncate(file.get(), lookupChars);
            }
            List<SourceIndex.Hit> named = filesNamedIn(query);
            // A question that names several types is asking what they look like, not for prose
            // about one of them: one section of documentation then, and the room goes to code.
            // One section, not two, when the question is a list of type names or a single type
            // name: neither is asking for prose. Two full-size guide sections in front of a
            // one-word question is most of a worker's answer spent on what it did not ask.
            boolean aName = named.size() >= 2 || contentWords(query) <= 1;
            int maxSections = aName ? 1 : 2;
            KnowledgeCurator.RelevantDocsResult docsResult = curator.relevantDocsExcluding(
                query, maxSections, aName ? docChars / 2 : docChars, false, alreadyGivenSections,
                newTerms);
            if (docsResult.allMatchesAlreadyGiven()) {
                return nothingNewMessage(docsResult.matchedSections());
            }
            String docs = docsResult.rendered();
            // The code half takes what the prose half leaves: about 2,000 characters at least,
            // since the prose half is capped, and the whole answer when there is no documentation.
            int margin = 200 + 2 * query.length(); // the two headings that repeat the query
            int codeRoom = lookupChars - docs.length() - margin;
            String code = shapedSources(query, codeRoom, named);
            if (!docs.isBlank() || !code.isBlank()) {
                StringBuilder answer = new StringBuilder();
                if (!docs.isBlank()) {
                    answer.append("Documentation for \"").append(query).append("\":\n").append(docs);
                }
                if (!code.isBlank()) {
                    answer.append("\nReal code from this project that matches \"").append(query)
                          .append("\" — this is what the API actually looks like:\n")
                          .append(code);
                }
                return truncate(answer.toString(), lookupChars);
            }
            Optional<String> hit = docsIndex == null ? Optional.empty() : docsIndex.lookupApi("", query);
            if (hit.isPresent() && !hit.get().isBlank()) {
                return truncate(hit.get(), lookupChars);
            }
            // Context7 fall-through: resolve the first token as a library name, fetch, retry.
            // This is the workers' route to a library that is in no reference folder — the
            // Librarian fetches on their behalf, which is why a worker needs no MCP tool of its
            // own (author decision 2026-08-28: one question, one tool). It is LAST on purpose:
            // Context7 serves the published release, the reference folder is the operator's live
            // checkout, and confidently-applied stale documentation is its own failure mode.
            String library = query.split("[\\s.]+")[0];
            Optional<String> fetched = fetchLibraryDocs(library, query);
            if (fetched.isPresent() && !fetched.get().isBlank()) {
                // It is in the one index now, so ask the one index: the answer comes back as the
                // SECTION that matches, attributed to the library it came from, rather than as the
                // whole reply of whichever fetch happened to be stored first.
                String indexed = curator.relevantDocs(query, 2, docChars);
                if (!indexed.isBlank()) {
                    return truncate("Documentation for \"" + query + "\":\n" + indexed,
                        lookupChars);
                }
                return truncate(fetched.get(), lookupChars);
            }
        } catch (Exception e) {
            log.warn("lookup_api('{}') failed: {}", query, e.getMessage());
        }
        return notFound(query, reader);
    }

    // --- the code half, shaped to the question ------------------------------------------------

    /** A file named by path: {@code "Button.java"}, {@code "zeroz4j/.../Button.java lines 40-120"}. */
    private static final Pattern PATH_REQUEST =
        Pattern.compile("([A-Za-z0-9_][A-Za-z0-9_./\\\\-]*\\.java)\\b");
    private static final Pattern LINE_RANGE =
        Pattern.compile("(?i)\\b(?:lines?|L)\\s*(\\d+)\\s*(?:-|–|to)\\s*(\\d+)");
    /** How many files one answer may shape when a question names several types. */
    private static final int MAX_NAMED_FILES = 3;
    /**
     * Below this many words of its own, a question is a name and gets the file with that name.
     * At or above it, it is a question about doing something and the ranking fills the remaining
     * room with the files that do it.
     */
    private static final int FILL_MIN_WORDS = 3;
    /** Below this much room a shaped file is all note and no signatures; it is named instead. */
    private static final int MIN_SHAPED_CHARS = 900;

    /**
     * The whole-file tier: a worker that asks for a file by its path gets the file, and nothing
     * else — no documentation half, because it did not ask a question. A line range continues a
     * file that did not fit; the note on the first answer says which range to ask for.
     */
    private Optional<String> fileByPath(String query, int lookupChars) {
        Matcher path = PATH_REQUEST.matcher(query == null ? "" : query);
        if (!path.find()) {
            return Optional.empty();
        }
        String wanted = path.group(1).replace('\\', '/');
        Optional<SourceIndex.Hit> hit = sources.byAddress(wanted);
        if (hit.isEmpty()) {
            String name = wanted.substring(wanted.lastIndexOf('/') + 1);
            List<SourceIndex.Hit> named = sources.byName(name);
            hit = named.isEmpty() ? Optional.empty() : Optional.of(named.get(0));
        }
        if (hit.isEmpty()) {
            return Optional.empty();
        }
        String source = read(hit.get());
        if (source.isEmpty()) {
            return Optional.empty();
        }
        Matcher range = LINE_RANGE.matcher(query);
        int from = 1;
        int to = 0;
        if (range.find()) {
            from = Integer.parseInt(range.group(1));
            to = Integer.parseInt(range.group(2));
        }
        SourceShaper.Answer answer = SourceShaper.whole(hit.get().address(),
            JavaOutline.of(source), from, to, lookupChars - 200);
        return Optional.of(answer.render(hit.get().address()));
    }

    /**
     * The source half of an answer, shaped by {@link SourceShaper} to what the question names.
     *
     * <p>Which file: a question that names a type gets that type's own file — "what is on
     * ClickEvent" is answered by {@code ClickEvent.java}, not by the file that mentions click
     * events most, which is what the keyword scorer picks and what used to be returned. Several
     * named types get several files, in the order asked, while the budget lasts. A question that
     * names no type falls back to the scorer's pick.
     */
    private String shapedSources(String query, int maxChars, List<SourceIndex.Hit> named) {
        List<SourceIndex.Hit> files = new ArrayList<>(named);
        // The named types first, then the index's own best answers to fill what is left.
        //
        // It used to be one or the other: the ranked pick was consulted ONLY when the question
        // named no type at all. Measured 2026-09-03, that is what made twelve questions about
        // reaching a store return a server bootstrap class and nothing else — the question named
        // one type, so the file that actually shows the API being used was never looked for. A
        // question is not answered by the one word in it that happens to be capitalised.
        Set<String> chosenNames = new java.util.HashSet<>();
        files.forEach(hit -> chosenNames.add(simpleName(hit)));
        // Only a question with something in it beyond the type it names. "TextField" is answered
        // by TextField and by nothing else: adding the two files that mention text fields most
        // spends the worker's context on material it did not ask for, and the whole point of
        // shaping an answer to the size of its question was not to do that.
        if (files.isEmpty() || contentWords(query) >= FILL_MIN_WORDS) {
            for (ReferenceIndex.SrcHit hit : curator.sourceHits(query, 3 * MAX_NAMED_FILES)) {
                if (files.size() > MAX_NAMED_FILES) {
                    break;
                }
                // One file per type name. Seven examples each declare a DataRoot; showing three of
                // them spends the whole code budget saying the same thing three times, and the
                // file the worker needed next — the provider that makes the root — never fits.
                sources.byAddress(hit.address())
                    .filter(candidate -> !files.contains(candidate))
                    .filter(candidate -> chosenNames.add(simpleName(candidate)))
                    .ifPresent(files::add);
            }
        }
        StringBuilder sb = new StringBuilder();
        List<String> notShown = new ArrayList<>();
        // The types the QUESTION named, which is what decides whether the "not declared here"
        // hint is useful. A file the ranking filled in was not named by anyone, so it must not
        // suppress a hint about the type that was.
        Set<String> names = new java.util.HashSet<>();
        named.forEach(hit -> names.add(simpleName(hit)));
        int alsoNamedLine = files.size() > 1 ? 140 : 0;
        int shaped = 0;
        for (SourceIndex.Hit hit : files) {
            String source = read(hit);
            if (source.isEmpty()) {
                continue;
            }
            if (shaped >= MAX_NAMED_FILES) {
                notShown.add(simpleName(hit));
                continue;
            }
            String others = otherFilesNamed(hit);
            // What the file's own heading and fence cost, and the line naming files not shown.
            int overhead = others.length() + hit.address().length() + 40 + alsoNamedLine;
            int room = maxChars - sb.length() - overhead;
            // Under this, a surface has room for its note and nothing else — say so instead.
            if (room < MIN_SHAPED_CHARS) {
                notShown.add(simpleName(hit));
                continue;
            }
            Set<String> otherTypes = new java.util.HashSet<>(names);
            otherTypes.remove(simpleName(hit));
            String block = SourceShaper.shape(hit.address(), source, query, room, otherTypes)
                .render(hit.address()) + others;
            if (sb.length() + block.length() + alsoNamedLine > maxChars) {
                notShown.add(simpleName(hit));
                continue;
            }
            sb.append(block);
            shaped++;
        }
        if (!notShown.isEmpty()) {
            sb.append("[Also named, not shown for room: ").append(String.join(", ", notShown))
              .append(" — ask lookup_api for each one.]\n");
        }
        return sb.toString();
    }

    /** One line naming the other files with this one's name, so a worker can ask for one. */
    private String otherFilesNamed(SourceIndex.Hit hit) {
        List<SourceIndex.Hit> others = sources.byName(simpleName(hit));
        if (others.size() < 2) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[Other files named ").append(simpleName(hit)).append(": ");
        int n = 0;
        for (SourceIndex.Hit other : others) {
            if (!other.equals(hit) && n++ < 3) {
                sb.append(n > 1 ? ", " : "").append(other.address());
            }
        }
        return sb.append(" — ask lookup_api by that path to see one.]\n").toString();
    }

    /**
     * The files a question names, by simple name — all of them, so that none is dropped without
     * being named in the answer. A capitalised word is a type name and always counts; a
     * lower-case word counts only when it names a file AND does not occur inside a file a
     * capitalised word already chose — "button click listener ClickEvent" gets both
     * {@code ClickEvent} and {@code Button}, "TextField getValue setValue label" gets
     * {@code TextField} alone and not {@code Label}, because "label" is a word inside it.
     */
    private List<SourceIndex.Hit> filesNamedIn(String query) {
        List<SourceIndex.Hit> chosen = new ArrayList<>();
        List<String> chosenText = new ArrayList<>();
        List<String> lowerCandidates = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (String token : (query == null ? "" : query).split("[^A-Za-z0-9_]+")) {
            String lower = token.toLowerCase(java.util.Locale.ROOT);
            if (token.length() < 4 || !seen.add(lower) || SourceShaper.NOISE.contains(lower)) {
                continue;
            }
            if (!Character.isUpperCase(token.charAt(0))) {
                lowerCandidates.add(token);
                continue;
            }
            SourceIndex.Hit hit = bestNamed(token, query);
            if (hit != null && !chosen.contains(hit)) {
                chosen.add(hit);
                chosenText.add(read(hit).toLowerCase(java.util.Locale.ROOT));
            }
        }
        for (String token : lowerCandidates) {
            String lower = token.toLowerCase(java.util.Locale.ROOT);
            boolean insideChosen = chosenText.stream().anyMatch(text -> text.contains(lower));
            if (insideChosen) {
                continue;
            }
            SourceIndex.Hit hit = bestNamed(token, query);
            if (hit != null && !chosen.contains(hit)) {
                chosen.add(hit);
                chosenText.add(read(hit).toLowerCase(java.util.Locale.ROOT));
            }
        }
        return chosen;
    }

    /**
     * Of several files with one name: not a test, then one whose path says another query word.
     *
     * <p>A name that resolves ONLY to an archetype skeleton or a smoke fixture resolves to
     * nothing, and the question falls through to the ranked index instead. Measured: a worker
     * asked for "inventory-crud-server ServerApp main" and was handed the archetype's
     * {@code ServerApp.java}, which has no store in it at all — because the examples call their
     * server class {@code ExampleServer} and the only file in the folder named {@code ServerApp}
     * is a template. A template that matches the word is worse than no match: it looks like an
     * answer.
     */
    private SourceIndex.Hit bestNamed(String name, String query) {
        List<SourceIndex.Hit> hits = sources.byName(name).stream()
            .filter(hit -> FileKinds.of(hit.address()).isAnswerable())
            .toList();
        if (hits.isEmpty()) {
            return null;
        }
        String[] words = query.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+");
        SourceIndex.Hit best = hits.get(0);
        int bestMatches = -1;
        for (SourceIndex.Hit hit : hits) {
            if (hit.test()) {
                break;
            }
            String address = hit.address().toLowerCase(java.util.Locale.ROOT);
            // The MOST of the question's words, not the first one that matches anywhere. Seven
            // examples in the reference folder each have a DataRoot.java and every one of their
            // paths contains the word "server", so "inventory-crud-server … DataRoot" was answered
            // with the chat example's — the first path that happened to say "server".
            int matches = 0;
            for (String word : words) {
                if (word.length() > 3 && !word.equals(name.toLowerCase(java.util.Locale.ROOT))
                    && address.contains(word)) {
                    matches++;
                }
            }
            if (matches > bestMatches) {
                bestMatches = matches;
                best = hit;
            }
        }
        return best;
    }

    /** Words of substance in a question — length over three, and not a word about asking. */
    private static int contentWords(String query) {
        int words = 0;
        for (String token : (query == null ? "" : query).split("[^A-Za-z0-9]+")) {
            if (token.length() > 3
                && !SourceShaper.NOISE.contains(token.toLowerCase(java.util.Locale.ROOT))) {
                words++;
            }
        }
        return words;
    }

    private static String simpleName(SourceIndex.Hit hit) {
        String file = hit.file().getFileName().toString();
        return file.substring(0, file.length() - 5);
    }

    private static String read(SourceIndex.Hit hit) {
        try {
            return Files.readString(hit.file());
        } catch (Exception e) {
            log.warn("Could not read {}: {}", hit.address(), e.getMessage());
            return "";
        }
    }

    /**
     * What {@code lookup_api} says when the search genuinely matched something for this
     * question, and every section it matched is one this worker has already been shown —
     * distinguished from {@link #notFound} on purpose, because there IS documentation on this;
     * there is no MORE of it, and reading further will not turn up anything the worker does not
     * already have.
     */
    private static String nothingNewMessage(List<String> alreadyGivenSections) {
        return NOTHING_NEW + String.join(", ", alreadyGivenSections) + ". There is no more "
            + "documentation on this; write your best attempt, or ask_expert with the exact question.";
    }

    /** How {@link #nothingNewMessage} begins — so a reader that is not a worker can recognise it. */
    static final String NOTHING_NEW = "Every documentation section matching this lives in what you "
        + "already read: ";

    /**
     * The miss message. It used to end at "inspect the code directly with read/exec", which is
     * the sentence that authorised ten workers to spend a whole run inside {@code javap} — the
     * tool told them to. When documents DO exist it now names them and asks for another word;
     * when they genuinely do not, it says so plainly and bounds what to do instead.
     */
    private String notFound(String query, MaterialBudget reader) {
        String mcpNote = documentationServerReachable() ? ""
            : " (The documentation server is not running, so published library docs could not be "
              + "consulted — do not wait for it and do not retry.)";
        String map = curator.documentationMap(reader.chars(MISS_MAP_CHARS));
        if (map.isBlank()) {
            return "No documentation found for \"" + query + "\", and this project has no "
                + "reference documentation folder." + mcpNote
                + " Read the code directly (grep for the symbol, or `javap -p` a class if there is "
                + "no source). Spend a few tool calls on it at most, then write the best code you "
                + "can and let the build tell you what is wrong — a failing compile is far cheaper "
                + "than reverse-engineering a whole library.";
        }
        return "No documentation matched \"" + query + "\". Try one word — a class name or a "
            + "topic." + mcpNote + " These documents exist and are searchable:\n" + map;
    }

    private static final int LOOKUP_CHARS = 6_000;
    /**
     * How the one lookup answer is split between prose and code. Prose may take up to 3,000
     * characters; code takes whatever prose leaves, about 2,600 at least. These are ceilings,
     * not targets: since the code half is shaped to the question (a type's public surface, or the
     * one method asked about — see {@link SourceShaper}), an answer is usually well under both.
     *
     * <p>It was 3,600, and two full-size guide sections then left the code half with room for one
     * shaped file. One file is not enough when the question is "how do I do X": the answer is a
     * guide page AND the two or three files that do it — the service that saves, the root object
     * it saves into, and the thing that makes the root. 3,000 buys the second and third file for
     * 600 characters of prose the worker was going to skim anyway.
     */
    private static final int DOC_CHARS = 3_000;

    private static String truncate(String text, int lookupChars) {
        return text.length() <= lookupChars ? text
            : text.substring(0, lookupChars) + "\n[truncated]";
    }
}
