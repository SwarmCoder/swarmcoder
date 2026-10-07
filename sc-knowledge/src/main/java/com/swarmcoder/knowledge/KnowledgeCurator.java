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

import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.inference.VllmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.LinkedHashSet;

/**
 * The Librarian's v2 knowledge layer (author requirement 2026-07-14): agents get code
 * conventions, codebase rules, best practices, and REAL source examples — not tree-sitter
 * signature dumps. Three sources, all capped and deterministic:
 *
 * <ol>
 *   <li><b>Curated docs</b> — {@code .swarmcoder/knowledge/*.md} plus {@code README*.md}
 *       from each root: the human-authored conventions and rules channel.</li>
 *   <li><b>Distilled primer</b> — a one-time LLM distillation per context root (purpose,
 *       core concepts, key APIs with exact signatures, one canonical example, dos/don'ts),
 *       cached on disk keyed by the root's content fingerprint.</li>
 *   <li><b>Relevant sources and documentation</b> — chosen by {@link ReferenceIndex}, a BM25
 *       index over every file under the roots, ranked by what each file IS
 *       ({@link FileKinds}) as well as by its words. This class no longer scores anything
 *       itself; it decides what to ASK for and how much of the answer fits.</li>
 * </ol>
 *
 * <p><b>Nothing here is capped by file count any more.</b> It used to keep 400 source files per
 * root in path order, which on a 681-file reference folder made five whole modules — the
 * framework's own API, the store wiring, every UI component — unreachable by any query.
 */
public final class KnowledgeCurator {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCurator.class);
    private static final int MAX_FILE_SCAN_BYTES = 24 * 1024;
    /**
     * What the primer is distilled FROM, at the baseline room — and the two per-file read caps
     * inside it, {@link #PRIMER_GUIDE_CHARS} and {@link #PRIMER_EXAMPLE_CHARS}.
     *
     * <p><b>Scaled by the PRIMER MODEL's own room since 2026-09-25</b> (see {@code MaterialBudget}),
     * because this is the input of one call to that model and nobody else reads it. In production
     * the primer model is the utility role, on the generic 32,768-token cloud shape, so the input
     * is exactly what it was; a primer model configured with a bigger room reads more of the
     * framework's guides, and more of each, before it distils. {@link #PRIMER_MAX_FILES} is a count
     * and does not move.
     */
    private static final int PRIMER_INPUT_CHARS = 26_000;
    private static final int PRIMER_GUIDE_CHARS = 6_000;
    private static final int PRIMER_EXAMPLE_CHARS = 4_000;
    private static final int PRIMER_MAX_FILES = 8;

    /**
     * The primer's cache key carries this, so that changing what a primer is DISTILLED FROM
     * invalidates every primer already on disk. Without it the nine primers already cached for
     * one reference folder would go on teaching a persistence API the project's dependencies do
     * not provide, because the folder's content fingerprint had not moved.
     *
     * <p>A primer distilled from a scaled input is a different distillate, so its key also carries
     * the input size ({@link #primerRecipe}); at the baseline the key is this string alone and
     * every primer already on disk stays valid.
     */
    private static final String PRIMER_RECIPE = "v2";

    /**
     * Folder names that hold written documentation. A reference framework's prose is the
     * cheapest thing a worker can be given and it used to be invisible: {@code conventions()}
     * listed only the root folder, non-recursively, and kept only files whose NAME started with
     * "readme" — so a project pointed at a framework with 40 documents under {@code docs/} was
     * handed its top-level README and nothing else, and ten workers spent an entire run
     * decompiling the jar instead (§32).
     */
    private static final Set<String> DOC_DIRS = Set.of("docs", "doc", "documentation");
    private static final int MAX_DOC_FILES_PER_ROOT = 300;
    /**
     * One rendered documentation section; longer sections are trimmed, never dropped.
     *
     * <p><b>A floor since 2026-09-25, not the whole rule.</b> A section may be as long as its fair
     * share of the caller's budget ({@code maxChars / maxSections}), and never shorter than this.
     * Every caller at the baseline room asks for at most 2,000 characters a section, so every one
     * of them still trims at exactly 3,500. A caller sized for a bigger room — a DeepSeek worker's
     * {@code lookup_api} asks for two sections in 15,360 characters — gets whole sections instead
     * of the first 3,500 characters of each; without that, the documentation half of a scaled
     * answer could never have grown past two trimmed sections whatever its budget said. The
     * failure this trim once caused — a long component reference cut before the entry the query
     * was about — is why trimming happens at render and not at indexing, and a longer window only
     * makes it rarer.
     */
    private static final int MAX_SECTION_CHARS = 3_500;

    /**
     * The size a section can be shown at in full, and therefore the point at which a section
     * starts paying for being longer than the worker will ever see of it.
     *
     * <p><b>Deliberately NOT scaled with any reader's room</b> (2026-09-25), although
     * {@link #MAX_SECTION_CHARS} now grows with the caller's budget. This number sits in the
     * ranking ({@code ReferenceIndex.lengthFactor}), and a ranking that depended on who is asking
     * would give a worker and the expert different sections for the same question, and would
     * silently re-tune an ordering that was measured against real questions at this value. Whether
     * a bigger window should relax the length penalty is a question for a measurement, not a
     * side effect of a budget.
     */
    static final int REFERENCE_SECTION_CHARS = 3_500;

    /**
     * The per-file read cap in {@link #relevantSources} — a floor, like {@link #MAX_SECTION_CHARS}:
     * a file may be as long as its fair share of the caller's budget and never shorter than this.
     * At the baseline every caller's share is 3,000 characters or less, so this is what every file
     * was read at before.
     */
    private static final int SOURCE_FILE_CHARS = 4_500;

    /** The per-README read cap in {@link #conventions}; a floor, as above. */
    private static final int ENTRY_DOC_CHARS = 6_000;

    /**
     * A knowledge root: the project itself or a read-only context folder.
     *
     * <p>It carries its VERSION, because an answer out of the wrong version of a framework is
     * worse than no answer — it compiles in the worker's head and fails in the build. The version
     * is {@code git describe --tags --always} for a checkout, else the folder's own pom version,
     * else "unknown"; {@code identity()} adds the commit and whether the tree is dirty.
     */
    public record Root(String label, Path path, String version) {

        public Root(String label, Path path) {
            this(label, path, RootIdentity.of(path, "project".equals(label)).version());
        }

        /** Version, commit and dirtiness, recomputed at most once a minute. */
        RootIdentity.Identity identity() {
            return RootIdentity.of(path, "project".equals(label));
        }
    }

    private final List<Root> roots;
    private final VllmClient primerModel; // nullable — primer distillation skipped without it
    private final Path primerCacheDir;
    private final ReferenceIndex index;
    private final Path resolvedIndexRoot;
    private final List<String> siteUrls;
    private volatile List<FileEntry> inventoryCache;
    private volatile List<FileEntry> docInventoryCache;
    private volatile String primerCache;
    private volatile List<WorkedExamples.Shape> shapeCache;
    private volatile SemanticIndex semanticCache;

    private record FileEntry(Root root, Path file, String relative) {}

    public KnowledgeCurator(List<Root> roots, VllmClient primerModel, Path primerCacheDir) {
        this(roots, primerModel, primerCacheDir,
            primerCacheDir == null ? null : primerCacheDir.resolve("refs"), null);
    }

    public KnowledgeCurator(List<Root> roots, VllmClient primerModel, Path primerCacheDir,
                            Path indexRoot, List<String> kindOverrides) {
        this(roots, primerModel, primerCacheDir, indexRoot, kindOverrides, List.of());
    }

    /**
     * @param indexRoot     where the persistent reference index lives; one subfolder per root set
     * @param kindOverrides per-project {@code kind: path-fragment} lines from the store, layered
     *                      over {@link FileKinds}' defaults; nullable
     */
    /**
     * @param siteUrls documentation SITES this project may read — base URLs, crawled once in the
     *                 background and indexed exactly like a folder's documents. Workers never
     *                 reach the network; this runs in the orchestrator process.
     */
    public KnowledgeCurator(List<Root> roots, VllmClient primerModel, Path primerCacheDir,
                            Path indexRoot, List<String> kindOverrides, List<String> siteUrls) {
        this.roots = roots == null ? List.of() : List.copyOf(roots);
        this.primerModel = primerModel;
        this.primerCacheDir = primerCacheDir;
        this.resolvedIndexRoot = indexRoot != null ? indexRoot
            : Path.of(System.getProperty("user.home"), ".swarmcoder", "rag", "refs");
        this.index = new ReferenceIndex(this.roots, this.resolvedIndexRoot, kindOverrides);
        this.siteUrls = siteUrls == null ? List.of() : List.copyOf(siteUrls);
        if (!this.siteUrls.isEmpty()) {
            Thread crawler = new Thread(this::indexSites, "docs-site-crawl");
            crawler.setDaemon(true);
            crawler.start();
        }
    }

    /**
     * Pulls every configured documentation site into the index. One page becomes one document,
     * addressed by its URL, so an answer says where it came from and a worker can ask for it by
     * name — the same contract a folder's page has.
     */
    private void indexSites() {
        WebDocs web = new WebDocs(resolvedIndexRoot.resolve("web"));
        for (String site : siteUrls) {
            try {
                WebDocs.Site crawled = web.crawl(site);
                for (WebDocs.Page page : crawled.pages()) {
                    index.addExternalDocument(crawled.baseUrl(), crawled.version(),
                        page.url(), page.markdown());
                }
                if (!crawled.pages().isEmpty()) {
                    log.info("Indexed {} pages of documentation from {} (version {})",
                        crawled.pages().size(), crawled.baseUrl(), crawled.version());
                }
            } catch (Exception e) {
                log.warn("Could not index the documentation site {}: {}", site, e.getMessage());
            }
        }
    }

    /**
     * Puts documentation fetched from a library server into the SAME index, split into sections
     * and tagged with the library it came from — so it is ranked against the folders' pages
     * instead of being consulted only after they have already failed.
     */
    public void indexLibraryDocs(String libraryId, String version, String content) {
        if (libraryId == null || content == null || content.isBlank()) {
            return;
        }
        String clean = libraryId.replaceAll("^/+", "").replace('/', '.');
        index.addExternalDocument(libraryId, version,
            "context7/" + clean + "@" + (version == null || version.isBlank() ? "latest" : version)
                + ".md", content);
    }

    /** Whether this documentation is already indexed, so a library is fetched once, not per ask. */
    public boolean holdsLibraryDocs(String libraryId, String version) {
        String clean = libraryId.replaceAll("^/+", "").replace('/', '.');
        return index.holds("context7/" + clean + "@"
            + (version == null || version.isBlank() ? "latest" : version) + ".md");
    }

    /** What the reference index cost to build — files, sections, milliseconds, bytes on disk. */
    ReferenceIndex.Stats indexStats() {
        return index.stats();
    }

    /** The knowledge roots, in order: the project first, then its read-only reference folders. */
    public List<Root> roots() {
        return roots;
    }

    /**
     * Every Java file under the roots, read once per process and kept.
     *
     * <p>Reference folders are read-only checkouts and the project moves under a worker's own
     * worktree, not under this one, so nothing here goes stale within a run. Walking 681 files
     * costs about two seconds; a task's brief is assembled per task and per wave, and paying that
     * every time would be the largest single cost in PLAN.
     */
    public List<WorkedExamples.Shape> shapes() {
        List<WorkedExamples.Shape> cached = shapeCache;
        if (cached == null) {
            synchronized (this) {
                if (shapeCache == null) {
                    long started = System.currentTimeMillis();
                    shapeCache = WorkedExamples.shapes(roots);
                    log.info("read the shape of {} Java files under {} root(s) in {} ms",
                        shapeCache.size(), roots.size(), System.currentTimeMillis() - started);
                }
                cached = shapeCache;
            }
        }
        return cached;
    }

    /**
     * The structural index over the same roots: what the code MEANS, where {@link #shapes()} is
     * what it LOOKS LIKE and {@link ReferenceIndex} is what it SAYS.
     *
     * <p><b>Lazy, and that is the whole cost story.</b> Building it is a type-attributed compile of
     * every Java file under every root — measured at 15.6 seconds and 340 KB on disk for the
     * 681-file reference checkout, and 186 ms to reopen once it is there
     * ({@code TheSemanticIndexOnTheRealCheckoutTest}). Nothing pays for it until something asks a
     * question it can answer, and after the first ask in the life of a checkout nobody pays for it
     * again.
     *
     * <p><b>It never fails a caller.</b> The result is always a {@link SemanticIndex}; when it
     * could not be built the index reports {@link SemanticIndex#available()} false, every query
     * comes back empty, and the caller carries on with the text index. See that class.
     */
    private volatile com.swarmcoder.lsp.LspService languageServer =
        com.swarmcoder.lsp.LspService.UNAVAILABLE;
    private volatile Path languageServerRoot;

    /**
     * The Java language server that reads {@code root}, one of this curator's roots - the
     * project's own checkout (2026-10-04). Asked through {@link LanguageQueries}; never set
     * means every such question is answered "not available".
     */
    public void languageServer(com.swarmcoder.lsp.LspService server, Path root) {
        this.languageServer = server == null ? com.swarmcoder.lsp.LspService.UNAVAILABLE : server;
        this.languageServerRoot = root;
    }

    /** Never null. */
    public com.swarmcoder.lsp.LspService languageServer() {
        return languageServer;
    }

    /** The label of the root the language server reads, or "" when there is none. */
    String languageServerRootLabel() {
        Path wanted = languageServerRoot == null ? null
            : languageServerRoot.toAbsolutePath().normalize();
        for (Root root : roots) {
            if (wanted != null && root.path() != null
                    && root.path().toAbsolutePath().normalize().equals(wanted)) {
                return root.label();
            }
        }
        return "";
    }

    public SemanticIndex semanticIndex() {
        SemanticIndex cached = semanticCache;
        if (cached == null) {
            synchronized (this) {
                if (semanticCache == null) {
                    try {
                        semanticCache = SemanticIndex.over(roots, resolvedIndexRoot);
                    } catch (Throwable e) {                                // noqa
                        log.warn("the semantic index could not be built ({}); everything falls "
                            + "back to the text index", e.toString());
                        semanticCache = SemanticIndex.unavailable(e.toString());
                    }
                }
                cached = semanticCache;
            }
        }
        return cached;
    }

    /**
     * The group id a reference folder publishes under — how a folder on disk is matched to the
     * dependency the project declares, so the two versions can be compared. Read from the root
     * pom, its parent block included, before any dependency block.
     */
    static String groupIdOf(Root root) {
        Path pom = root.path() == null ? null : root.path().resolve("pom.xml");
        if (pom == null || !Files.isRegularFile(pom)) {
            return "";
        }
        String text = readCapped(pom, 40_000);
        int dependencies = text.indexOf("<dependencies>");
        String head = dependencies > 0 ? text.substring(0, dependencies) : text;
        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile("<groupId>([^<]+)</groupId>").matcher(head);
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    // --- 1. curated docs -------------------------------------------------------------------------

    /**
     * Reference-repo entry docs: the top-level README of each root. Curated project knowledge
     * lives as {@code KnowledgeDoc} objects in the store (author decision: no markdown files)
     * and is rendered by the Librarian, not here.
     *
     * <p>Budget discipline: a block that does not fit is TRIMMED to what is left, never dropped.
     * It used to be dropped, and since each README was read at a 6,000-character cap while this
     * method was called with 3,000, the very first block always overflowed and the whole section
     * came back as the six words "… (conventions truncated)" — measured, not theorised (§32).
     */
    public String conventions(int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (Root root : roots) {
            for (FileEntry doc : docInventory()) {
                if (doc.root() != root || !isEntryDoc(doc.relative())) {
                    continue;
                }
                // A README is read at the caller's whole budget, never below 6,000 — which at the
                // baseline is every caller (the architect asks for 3,000 here).
                String body = readCapped(doc.file(), Math.max(ENTRY_DOC_CHARS, maxChars));
                if (body.isBlank()) {
                    continue;
                }
                if (!appendCapped(sb, "\n## [" + root.label() + "] " + doc.relative() + "\n"
                        + body + "\n", maxChars)) {
                    return sb.toString();
                }
            }
        }
        return sb.toString();
    }

    /** A root's own entry document — a top-level README/AGENTS/CONTRIBUTING style file. */
    private static boolean isEntryDoc(String relative) {
        return !relative.contains("/")
            && relative.toLowerCase(Locale.ROOT).startsWith("readme");
    }

    // --- 1b. the documentation channel ------------------------------------------------------

    /**
     * The catalogue of documentation the worker actually has: one line per document, with its
     * title. Tiny, deterministic, and it is what turns "I have no idea what this framework
     * offers" into "there is a UI_COMPONENTS.md — ask for it". Bodies are NOT included; they
     * are fetched on demand through {@code lookup_api}, which has no prefix-budget to respect.
     */
    public String documentationMap(int maxChars) {
        List<ReferenceIndex.DocEntry> docs = index.catalogue().stream()
            .filter(entry -> entry.kind() != FileKinds.Kind.META)
            .toList();
        if (docs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int listed = 0;
        for (ReferenceIndex.DocEntry doc : docs) {
            String line = "- " + doc.address() + " — " + doc.title()
                + (doc.summary() == null || doc.summary().isBlank() ? "" : ": " + doc.summary())
                + "\n";
            if (sb.length() + line.length() > maxChars) {
                break;
            }
            sb.append(line);
            listed++;
        }
        if (listed < docs.size()) {
            sb.append("- … and ").append(docs.size() - listed)
              .append(" more documents — ask lookup_api for any topic; it searches all of them.\n");
        }
        return sb.toString();
    }

    /**
     * Every document the roots hold, as {@code <root>/<relative path>} with its title, in the
     * catalogue's order - what {@link DocumentQueries} outlines and searches.
     */
    List<ReferenceIndex.DocEntry> documents() {
        return index.catalogue();
    }

    /** How many documents the roots hold — for the "there is nothing to read" decision. */
    public int documentCount() {
        return index.catalogue().size();
    }

    /**
     * The task-relevant SLICE of the documentation: whole markdown SECTIONS, keyword-scored
     * against {@code query} exactly the way {@link #relevantSources} scores files.
     *
     * <p>Sections, not files, because the budget forces it: one reference document here is
     * 46 KB and the entire knowledge brief is 16,000 characters. A section carries a real,
     * complete answer ("## FormLayout" with its methods and example) at a few hundred
     * characters, so several fit where not even one document would.
     */
    public String relevantDocs(String query, int maxSections, int maxChars) {
        return relevantDocs(query, maxSections, maxChars, false);
    }

    /**
     * As {@link #relevantDocs(String, int, int)}, but {@code referenceOnly} leaves out the
     * documents that describe the PROJECT rather than the software: its release history and how
     * to contribute to it.
     *
     * <p>Needed for the prompt prefix, which is not a search result. A changelog's "Added"
     * section is enormous and mentions everything the framework can do, so against a real task
     * description — a dozen terms about loans, shelves and due dates — it matches more distinct
     * terms than any focused reference section and wins. Measured on the owner's own project,
     * 2026-09-02: the entire task-relevant documentation slice in every worker's prefix was one
     * trimmed chunk of {@code CHANGELOG.md}, 873 tokens, holding nothing the worker could act on.
     * Capping the per-term hits at three (§32) narrowed that gap and did not close it, because
     * the problem is not repetition — it is that a release note legitimately mentions every
     * subject at once.
     *
     * <p>Nothing becomes unreachable: {@code lookup_api} still passes {@code false} here, so a
     * worker that asks what changed in a release still gets the changelog. Only the prefix —
     * paid by every worker on every task, whether it wanted it or not — stops spending on it.
     */
    public String relevantDocs(String query, int maxSections, int maxChars, boolean referenceOnly) {
        return renderSections(scoreSections(query, referenceOnly), maxSections, maxChars);
    }

    /**
     * As {@link #relevantDocs(String, int, int, boolean)}, but {@code excludeSectionIds} — every
     * section (by {@link #sectionKey}) already handed to THIS WORKER by an earlier call — is
     * skipped, and {@code boostTerms} re-weights the ranking toward the words this question added
     * since the worker's earlier ones (see {@link ReferenceIndex#searchDocs(String, int, boolean,
     * Set)}).
     *
     * <p>{@link RelevantDocsResult#allMatchesAlreadyGiven()} is true exactly when the search DID
     * match something — this is not a "nothing found" miss — but every section it matched is one
     * the worker has already read. That is the caller's cue to say so plainly instead of handing
     * back the same page a third time.
     */
    public RelevantDocsResult relevantDocsExcluding(String query, int maxSections, int maxChars,
                                                    boolean referenceOnly,
                                                    Set<String> excludeSectionIds,
                                                    Set<String> boostTerms) {
        List<ScoredSection> scored = scoreSections(query, referenceOnly, boostTerms);
        if (scored.isEmpty()) {
            return new RelevantDocsResult("", false, List.of());
        }
        Set<String> exclude = excludeSectionIds == null ? Set.of() : excludeSectionIds;
        List<ScoredSection> remaining = scored.stream()
            .filter(candidate -> !exclude.contains(sectionKey(candidate.value())))
            .toList();
        if (remaining.isEmpty()) {
            List<String> matched = scored.stream()
                .map(candidate -> sectionKey(candidate.value()))
                .distinct()
                .toList();
            return new RelevantDocsResult("", true, matched);
        }
        return new RelevantDocsResult(renderSections(remaining, maxSections, maxChars), false, List.of());
    }

    /**
     * What {@link #relevantDocsExcluding} answers with: the rendered slice, and — when the search
     * matched something but every match was already given — which sections those were, so the
     * caller can name them instead of the render coming back empty for no stated reason.
     */
    public record RelevantDocsResult(String rendered, boolean allMatchesAlreadyGiven,
                                     List<String> matchedSections) {}

    /** The stable identity of a section: what a worker is told it was given, and can be told again. */
    static String sectionKey(DocSection section) {
        return section.address() + " › " + section.heading();
    }

    /** How many sections of ONE page may fill a slice that is meant to survey the documentation. */
    private static final int MAX_SECTIONS_PER_PAGE = 2;

    /** One heading-delimited chunk of a markdown document. */
    public record DocSection(String address, String heading, String body) {}

    /**
     * @param score    the raw BM25 relevance over the boosted fields, scaled by 100
     * @param adjusted that score after the file's KIND and the section's length are applied;
     *                 what the ranking sorts by
     * @param mentions kept equal to {@code score}; the old term-count tie-breaker is gone, since
     *                 BM25 already weighs a rare word above a common one
     */
    record ScoredSection(DocSection value, int score, double adjusted, int mentions) {}

    List<ScoredSection> scoreSections(String query) {
        return scoreSections(query, false);
    }

    /**
     * The documentation sections that answer {@code query}, best first, from the BM25 index.
     *
     * <p>{@code score} is the raw relevance (BM25 summed over the boosted fields, scaled to a
     * whole number so it reads); {@code adjusted} is that after the file's KIND and the section's
     * length are applied, and it is what the order is by; {@code mentions} is kept as the raw
     * score so the record's shape does not change under its callers.
     */
    List<ScoredSection> scoreSections(String query, boolean referenceOnly) {
        return scoreSections(query, referenceOnly, Set.of());
    }

    /** As {@link #scoreSections(String, boolean)}, with the ranking's own distinguishing-term boost. */
    List<ScoredSection> scoreSections(String query, boolean referenceOnly, Set<String> boostTerms) {
        List<ScoredSection> scored = new ArrayList<>();
        for (ReferenceIndex.DocHit hit : index.searchDocs(query, 40, referenceOnly, boostTerms)) {
            int raw = (int) Math.round(hit.score() * 100);
            scored.add(new ScoredSection(
                new DocSection(hit.address(), hit.heading(), hit.body()), raw, raw, raw));
        }
        return scored;
    }

    /**
     * Renders up to {@code maxSections} scored sections (already in ranked order) within
     * {@code maxChars}, at most {@link #MAX_SECTIONS_PER_PAGE} from any one page. Shared by
     * {@link #relevantDocs} and {@link #relevantDocsExcluding} so the two render identically.
     */
    private static String renderSections(List<ScoredSection> scored, int maxSections, int maxChars) {
        if (scored.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int included = 0;
        int sectionChars = Math.max(MAX_SECTION_CHARS, maxChars / Math.max(1, maxSections));
        // At most two sections from one page. Measured on a real task: a guide about answering in
        // the reader's LANGUAGE took all four slots of a task about a reader's borrowed books,
        // because "reader" means two different things and one page happened to own the word. Four
        // sections of one page is one page's worth of answer at four pages' cost.
        Map<String, Integer> perPage = new java.util.HashMap<>();
        for (ScoredSection candidate : scored) {
            if (included >= maxSections) {
                break;
            }
            DocSection section = candidate.value();
            if (perPage.merge(section.address(), 1, Integer::sum) > MAX_SECTIONS_PER_PAGE) {
                continue;
            }
            String block = "\n#### " + section.address() + " › " + section.heading() + "\n"
                + trimSection(section.body(), sectionChars) + "\n";
            if (!appendCapped(sb, block, maxChars)) {
                break;
            }
            included++;
        }
        return sb.toString();
    }

    /**
     * Splits a markdown document at its {@code #}/{@code ##}/{@code ###} headings. Fenced code
     * blocks are respected, so a {@code # comment} line inside a Java example cannot split a
     * section in half.
     */
    static List<DocSection> sectionsOf(String address, String markdown) {
        List<DocSection> sections = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return sections;
        }
        String heading = "(preamble)";
        StringBuilder body = new StringBuilder();
        boolean inFence = false;
        for (String line : markdown.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
            }
            if (!inFence && trimmed.matches("^#{1,3} .*")) {
                addSection(sections, address, heading, body.toString());
                heading = trimmed.replaceFirst("^#{1,3} ", "").strip();
                body.setLength(0);
                continue;
            }
            body.append(line).append('\n');
        }
        addSection(sections, address, heading, body.toString());
        return sections;
    }

    /**
     * Sections keep their FULL text; trimming happens at render. Trimming first was a real
     * defect: a long component reference was cut before the entry the query was about, so it
     * scored zero for its own subject and a passing mention elsewhere outranked it.
     */
    private static void addSection(List<DocSection> sections, String address,
                                   String heading, String body) {
        String text = body.strip();
        if (!text.isEmpty()) {
            sections.add(new DocSection(address, heading, text));
        }
    }

    /** One section as it goes into a prompt or a tool result, at {@code sectionChars} at most. */
    private static String trimSection(String body, int sectionChars) {
        return body.length() <= sectionChars ? body
            : body.substring(0, sectionChars) + "\n… (section trimmed)";
    }

    /**
     * Appends {@code block} within {@code maxChars}, trimming it to the remaining room rather
     * than dropping it. Returns false when there was no usable room left.
     */
    private static boolean appendCapped(StringBuilder sb, String block, int maxChars) {
        int room = maxChars - sb.length();
        if (room < 200) {
            return false;
        }
        if (block.length() <= room) {
            sb.append(block);
            return true;
        }
        sb.append(block, 0, room - 20).append("\n… (trimmed)\n");
        return false;
    }

    // --- 2. distilled primer ---------------------------------------------------------------------

    /**
     * The per-context-root framework primer, LLM-distilled once and cached on disk. Returns
     * "" when no model is wired and no cache exists — everything degrades, nothing fails.
     */
    public String primer(int maxChars) {
        String cached = primerCache;
        if (cached == null) {
            StringBuilder sb = new StringBuilder();
            for (Root root : roots) {
                if (isProjectRoot(root)) {
                    continue; // primers are for reference frameworks, not the work repo
                }
                String primer = primerFor(root);
                if (!primer.isBlank()) {
                    sb.append("\n## Framework primer: ").append(root.label()).append('\n')
                      .append(primer).append('\n');
                }
            }
            cached = sb.toString();
            primerCache = cached;
        }
        return cached.length() <= maxChars ? cached
            : cached.substring(0, maxChars) + "\n… (primer truncated)\n";
    }

    /**
     * The reference folders - every root except the project itself - by label, in the order they
     * were configured. What a worker's container mounts read-only beside its checkout.
     */
    public java.util.Map<String, Path> referenceFolders() {
        java.util.Map<String, Path> folders = new java.util.LinkedHashMap<>();
        for (Root root : roots) {
            if (!isProjectRoot(root) && root.path() != null && Files.isDirectory(root.path())) {
                folders.put(root.label(), root.path());
            }
        }
        return folders;
    }

    private boolean isProjectRoot(Root root) {
        return "project".equals(root.label());
    }

    private String primerFor(Root root) {
        try {
            String fingerprint = root.identity().fingerprint();
            Path cacheFile = primerCacheDir == null ? null
                : primerCacheDir.resolve(root.label() + "-" + primerRecipe() + "-" + fingerprint + ".md");
            if (cacheFile != null && Files.exists(cacheFile)) {
                return Files.readString(cacheFile);
            }
            if (primerModel == null) {
                return "";
            }
            String input = primerInput(root);
            if (input.isBlank()) {
                return "";
            }
            log.info("Distilling framework primer for context root '{}' (one-time, cached)", root.label());
            String primer = primerModel.as("knowledge").chatCompletionStream(List.of(
                    Map.of("role", "system", "content",
                        "You distill a FRAMEWORK PRIMER for coding agents from the source excerpts "
                        + "given. Sections: 1) Purpose & architecture (3 sentences max). "
                        + "2) Core concepts. 3) Key APIs — exact class/method signatures COPIED "
                        + "from the input, never invented. 4) One canonical usage example, "
                        + "verbatim or lightly trimmed from the input. 5) Conventions, rules and "
                        + "dos/don'ts evident in the code. Markdown, under 500 lines. If something "
                        + "is not in the input, do not mention it."),
                    Map.of("role", "user", "content", input)), null, 0.2)
                .collect(Collectors.joining());
            if (cacheFile != null && !primer.isBlank()) {
                Files.createDirectories(primerCacheDir);
                Files.writeString(cacheFile, primer);
            }
            return primer;
        } catch (Exception e) {
            log.warn("Primer distillation for '{}' failed: {}", root.label(), e.getMessage());
            return "";
        }
    }

    /**
     * What the primer is distilled FROM: the framework's own guides, then example code the
     * project's dependencies actually provide.
     *
     * <p><b>Why it changed.</b> It used to be the README plus the first eight example files in
     * path order, and on the reference folder this was measured against that is the {@code
     * chat-events} example — which uses the ZeroZ DB transaction API. So every worker's prefix
     * carried a primer teaching {@code db.localDb().write(...)} and {@code ctx.edit(...)}, while
     * the framework's own persistence guide teaches {@code EmbeddedStorageManager.store(...)},
     * and the project's dependencies provided neither of the two consistently. A worker asked
     * {@code lookup_api} twelve questions in a row mixing both vocabularies: it did not know which
     * stack it had, because the two authorities it had been given disagreed.
     *
     * <p>Guides first, and two thirds of the room, because a guide is written to be the answer.
     * The example code that follows is drawn only from example modules whose own dependencies are
     * a subset of what the project depends on, ranked by how much they overlap — so the primer
     * cannot teach an API the project cannot call.
     */
    String primerInput(Root root) {
        MaterialBudget room = primerRoom();
        int inputChars = room.chars(PRIMER_INPUT_CHARS);
        StringBuilder sb = new StringBuilder();
        int guideRoom = (inputChars * 2) / 3;
        for (ReferenceIndex.DocEntry doc : index.catalogue()) {
            if (doc.kind() != FileKinds.Kind.GUIDE || !doc.address().startsWith(root.label() + "/")) {
                continue;
            }
            Path file = resolve(doc.address());
            if (file == null) {
                continue;
            }
            String block = "\n===== " + doc.address() + " =====\n"
                + readCapped(file, room.chars(PRIMER_GUIDE_CHARS)) + "\n";
            if (sb.length() + block.length() > guideRoom) {
                continue;
            }
            sb.append(block);
        }
        for (FileEntry entry : primerExamples(root)) {
            String block = "\n===== " + entry.relative() + " =====\n"
                + readCapped(entry.file(), room.chars(PRIMER_EXAMPLE_CHARS)) + "\n";
            if (sb.length() + block.length() > inputChars) {
                break;
            }
            sb.append(block);
        }
        return sb.toString();
    }

    /** The primer model's own room — the only model that reads the primer's input. */
    private MaterialBudget primerRoom() {
        return MaterialBudget.of(primerModel);
    }

    /**
     * {@link #PRIMER_RECIPE}, plus the input size when it is not the baseline's, so a primer
     * distilled from more of the framework is never mistaken for one distilled from less.
     */
    String primerRecipe() {
        MaterialBudget room = primerRoom();
        return room.atBaseline() ? PRIMER_RECIPE
            : PRIMER_RECIPE + "-in" + room.chars(PRIMER_INPUT_CHARS);
    }

    /**
     * Example source files from the example module whose dependencies best match the project's,
     * falling back to the framework's own {@code src/main} when a root has no examples.
     */
    private List<FileEntry> primerExamples(Root root) {
        Set<String> projectDeps = artifactIds(projectRoot());
        Map<String, List<FileEntry>> byModule = new java.util.LinkedHashMap<>();
        for (FileEntry entry : inventory()) {
            if (entry.root() != root || !entry.relative().endsWith(".java")) {
                continue;
            }
            FileKinds.Kind kind = FileKinds.of(root.label() + "/" + entry.relative());
            if (kind != FileKinds.Kind.EXAMPLE) {
                continue;
            }
            byModule.computeIfAbsent(exampleModule(entry.relative()), key -> new ArrayList<>())
                .add(entry);
        }
        if (byModule.isEmpty()) {
            return inventory().stream()
                .filter(entry -> entry.root() == root && entry.relative().endsWith(".java")
                    && FileKinds.of(root.label() + "/" + entry.relative()) == FileKinds.Kind.FRAMEWORK)
                .limit(PRIMER_MAX_FILES).toList();
        }
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String module : new java.util.TreeSet<>(byModule.keySet())) {
            Set<String> moduleDeps = artifactIds(root.path().resolve(module));
            int shared = 0;
            int missing = 0;
            for (String dependency : moduleDeps) {
                if (projectDeps.contains(dependency)) {
                    shared++;
                } else {
                    missing++;
                }
            }
            // A module that needs something the project has not got teaches an API the project
            // cannot call; that is worse than a module that teaches less.
            int score = shared - 2 * missing;
            if (score > bestScore) {
                bestScore = score;
                best = module;
            }
        }
        return byModule.get(best).stream()
            .filter(entry -> !entry.relative().contains("/src/test/"))
            .limit(PRIMER_MAX_FILES).toList();
    }

    /** The example module a file belongs to: the first two path segments of its relative path. */
    private static String exampleModule(String relative) {
        String[] segments = relative.split("/");
        return segments.length >= 2 ? segments[0] + "/" + segments[1] : segments[0];
    }

    private Path projectRoot() {
        for (Root root : roots) {
            if (isProjectRoot(root)) {
                return root.path();
            }
        }
        return null;
    }

    /** Every {@code <artifactId>} named by the poms under {@code dir} — a cheap dependency set. */
    private static Set<String> artifactIds(Path dir) {
        Set<String> ids = new LinkedHashSet<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return ids;
        }
        try (Stream<Path> walk = Files.walk(dir, 4)) {
            walk.filter(file -> file.getFileName().toString().equals("pom.xml"))
                .filter(file -> !file.toString().replace('\\', '/').contains("/target/"))
                .sorted()
                .forEach(file -> {
                    java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("<artifactId>([^<]+)</artifactId>")
                        .matcher(readCapped(file, 200_000));
                    while (matcher.find()) {
                        ids.add(matcher.group(1).strip());
                    }
                });
        } catch (Exception e) {
            log.debug("Could not read artifact ids under {}: {}", dir, e.getMessage());
        }
        return ids;
    }

    // --- 3. relevant full sources ------------------------------------------------------------

    /**
     * FULL trimmed sources of the files most relevant to {@code query}, keyword-scored over
     * paths and contents — the API-preemption channel: the worker sees the real classes it
     * is about to need.
     */
    public String relevantSources(String query, int maxFiles, int maxChars) {
        return relevantSources(query, maxFiles, maxChars, false);
    }

    /**
     * As {@link #relevantSources(String, int, int)}, but {@code codeOnly} excludes markdown.
     *
     * <p>Needed because {@code lookup_api} now answers with prose AND code in one result, and
     * markdown outscores source for exactly the questions where source is what is wanted: the
     * reference document says "FormLayout" a dozen times and the class that uses it says it twice.
     * Without this the code half returned the very document the prose half had just quoted, which
     * is worse than nothing — it spends the budget saying the same thing twice.
     */
    public String relevantSources(String query, int maxFiles, int maxChars, boolean codeOnly) {
        // codeOnly is true in effect whatever is passed: prose and code are separate channels in
        // the index, so a document can no longer win the channel that exists to show code. The
        // flag stays because callers pass it, and because it says what the channel is for.
        StringBuilder sb = new StringBuilder();
        int included = 0;
        int fileChars = Math.max(SOURCE_FILE_CHARS, maxChars / Math.max(1, maxFiles));
        for (ReferenceIndex.SrcHit candidate : sourceHits(query, Math.max(maxFiles, 6))) {
            if (included >= maxFiles) {
                break;
            }
            String body = readCapped(candidate.file(), fileChars);
            String label = candidate.address();
            String block = "\n## Source: " + label + "\n```java\n" + body + "\n```\n";
            // Trim to the room left rather than dropping the block: with a 4,500-character
            // per-file read cap and a 3,000-character budget, "drop what does not fit" meant
            // this method returned "" for EVERY query — which is what made lookup_api answer
            // "no documentation found, inspect the code directly" and sent workers to javap.
            if (!appendCapped(sb, block, maxChars)) {
                break;
            }
            included++;
        }
        return sb.toString();
    }

    /** The analyst's grounding block: conventions + primer + a compact file inventory. */
    public String analystContext(int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append(conventions(maxChars / 3));
        String map = documentationMap(maxChars / 6);
        if (!map.isBlank()) {
            sb.append("\n## Reference documentation (read it with your tools before guessing)\n")
              .append(map);
        }
        sb.append(primer(maxChars / 2));
        StringBuilder inventoryList = new StringBuilder("\n## Browsable files (use your tools)\n");
        int count = 0;
        for (FileEntry entry : inventory()) {
            if (count++ >= 120) {
                inventoryList.append("… and ").append(inventory().size() - 120).append(" more\n");
                break;
            }
            inventoryList.append(entry.root().label()).append('/').append(entry.relative()).append('\n');
        }
        if (sb.length() + inventoryList.length() <= maxChars) {
            sb.append(inventoryList);
        }
        return sb.length() <= maxChars ? sb.toString() : sb.substring(0, maxChars);
    }

    // --- confined file access (shared with the analyst's tools) ---------------------------------

    /** Lists a folder addressed as {@code <rootLabel>/<relative>}; "" lists the roots. */
    public String listFolder(String address) {
        if (address == null || address.isBlank()) {
            return roots.stream().map(Root::label).collect(Collectors.joining("\n"));
        }
        Path dir = resolve(address);
        if (dir == null || !Files.isDirectory(dir)) {
            return "error: not a folder: " + address;
        }
        try (Stream<Path> list = Files.list(dir)) {
            return list.sorted().map(f -> f.getFileName().toString()
                    + (Files.isDirectory(f) ? "/" : ""))
                .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /** Whether {@code <rootLabel>/<relative>} names a folder inside a root. */
    public boolean isFolder(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        Path dir = resolve(address);
        return dir != null && Files.isDirectory(dir);
    }

    /** Folders a tree listing never descends into: build output and tool state, never material. */
    private static final Set<String> TREE_SKIPPED = Set.of("target", ".git", "node_modules",
        ".idea", ".swarmcoder", ".gradle");

    /**
     * A folder as a tree, several levels deep, with every chain of single-folder directories
     * joined into one line — {@code src/main/java/com/example/store/} rather than five separate
     * listings.
     *
     * <p><b>Why this exists (harness run 37, 2026-09-25).</b> {@link #listFolder} lists ONE level,
     * which is fine for a person clicking through folders and ruinous for a model with a turn
     * allowance: a Java package path is six or seven folders deep, and the expert spent fifteen of
     * its thirty-one turns walking {@code src/test/java} → {@code com} → {@code zeroz4j} →
     * {@code store}, and later {@code project/bookshelf-demo-server} → {@code src} → {@code main}
     * → … → {@code server}, one call per folder, most answers under ten characters long. It ran
     * out of turns with the answer already read. One tree reaches the files of an ordinary module
     * from its root in a single call.
     *
     * <p><b>Breadth first.</b> The entries are chosen level by level, so when {@code maxEntries}
     * cuts the listing it cuts the deepest level, never a sibling module; a folder that was not
     * opened says how many entries it holds, so the model knows where to list next.
     *
     * <p>{@link #listFolder} is left exactly as it was: the console chat and the architect's tools
     * use it, neither was implicated, and a person browsing wants one level.
     *
     * @param maxDepth   how many levels below {@code address} to show; a joined chain is one level
     * @param maxEntries how many lines at most, over all levels
     */
    public String listTree(String address, int maxDepth, int maxEntries) {
        Path dir = address == null || address.isBlank() ? null : resolve(address);
        if (dir == null || !Files.isDirectory(dir)) {
            return "error: not a folder: " + address;
        }
        String top = address.replace('\\', '/');
        while (top.endsWith("/")) {
            top = top.substring(0, top.length() - 1);
        }
        TreeNode root = new TreeNode(dir, top, true);
        java.util.ArrayDeque<TreeNode> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int entries = 0;
        boolean cut = false;
        String example = null;
        int exampleDepth = -1;
        while (!queue.isEmpty()) {
            TreeNode node = queue.poll();
            if (node.depth >= maxDepth) {
                continue;
            }
            List<Path> children = treeChildren(node.path);
            node.total = children.size();
            node.opened = true;
            for (Path child : children) {
                if (entries >= maxEntries) {
                    cut = true;
                    break;
                }
                TreeNode shown = collapsed(child, node);
                node.shown.add(shown);
                entries++;
                if (shown.folder) {
                    queue.add(shown);
                } else if (shown.depth > exampleDepth) {
                    // The deepest file teaches joining the lines best: the first one found at
                    // the deepest level, breadth first.
                    example = shown.address;
                    exampleDepth = shown.depth;
                }
            }
        }
        StringBuilder sb = new StringBuilder(top).append("/ — ").append(maxDepth)
            .append(" levels deep; a chain of single folders is joined into one line.");
        if (example != null) {
            sb.append(" A file's address is its folders joined, e.g. ").append(example).append('.');
        }
        sb.append('\n');
        render(root, "", sb);
        if (cut) {
            sb.append("(only the first ").append(maxEntries)
              .append(" entries are shown; list a subfolder to see the rest)\n");
        }
        return sb.toString();
    }

    private static final class TreeNode {
        final Path path;
        final String address;
        final String name;
        final boolean folder;
        final int depth;
        final List<TreeNode> shown = new ArrayList<>();
        int total = -1;
        boolean opened;

        TreeNode(Path path, String address, boolean folder) {
            this(path, address, address, folder, 0);
        }

        TreeNode(Path path, String address, String name, boolean folder, int depth) {
            this.path = path;
            this.address = address;
            this.name = name;
            this.folder = folder;
            this.depth = depth;
        }
    }

    private static List<Path> treeChildren(Path dir) {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> !(Files.isDirectory(p)
                    && TREE_SKIPPED.contains(p.getFileName().toString())))
                .sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** A child, with any chain of folders that each hold exactly one folder joined onto it. */
    private static TreeNode collapsed(Path child, TreeNode parent) {
        StringBuilder name = new StringBuilder(child.getFileName().toString());
        Path at = child;
        boolean folder = Files.isDirectory(child);
        for (int hops = 0; folder && hops < 32; hops++) {
            List<Path> inside = treeChildren(at);
            if (inside.size() != 1 || !Files.isDirectory(inside.get(0))) {
                break;
            }
            at = inside.get(0);
            name.append('/').append(at.getFileName());
        }
        return new TreeNode(at, parent.address + "/" + name, name.toString(), folder,
            parent.depth + 1);
    }

    private static void render(TreeNode node, String indent, StringBuilder sb) {
        for (TreeNode child : node.shown) {
            sb.append(indent).append(child.name);
            if (child.folder) {
                sb.append('/');
                if (!child.opened) {
                    int count = treeChildren(child.path).size();
                    sb.append("  (").append(count).append(count == 1 ? " entry)" : " entries)");
                }
            }
            sb.append('\n');
            if (child.folder && child.opened) {
                render(child, indent + "  ", sb);
            }
        }
        if (node.opened && node.shown.size() < node.total) {
            sb.append(indent).append("… ").append(node.total - node.shown.size())
              .append(" more not shown\n");
        }
    }

    /** Reads a file addressed as {@code <rootLabel>/<relative>}, capped. */
    public String readFile(String address, int maxChars) {
        Path file = resolve(address);
        if (file == null || !Files.isRegularFile(file)) {
            return "error: not a file: " + address + " (address as <root>/<relative-path>; "
                + "roots: " + roots.stream().map(Root::label).collect(Collectors.joining(", ")) + ")";
        }
        return readCapped(file, maxChars);
    }

    /** Case-insensitive substring search over sources; returns file:line matches, capped. */
    public String searchCode(String query, int maxMatches) {
        if (query == null || query.isBlank()) {
            return "error: empty query";
        }
        String needle = query.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        int matches = 0;
        for (FileEntry entry : inventory()) {
            if (matches >= maxMatches) {
                break;
            }
            String content = readCapped(entry.file(), MAX_FILE_SCAN_BYTES);
            String[] lines = content.split("\n");
            for (int i = 0; i < lines.length && matches < maxMatches; i++) {
                if (lines[i].toLowerCase(Locale.ROOT).contains(needle)) {
                    sb.append(entry.root().label()).append('/').append(entry.relative())
                      .append(':').append(i + 1).append(": ").append(lines[i].strip()).append('\n');
                    matches++;
                }
            }
        }
        return sb.isEmpty() ? "no matches for: " + query : sb.toString();
    }

    /**
     * File addresses ({@code <root>/<relative>}) matching a mention fragment — the backing
     * list for @-mention autocomplete. A blank query returns the first {@code max} files;
     * matches whose file name contains the fragment sort ahead of path-only matches.
     */
    public List<String> mentionCandidates(String query, int max) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<String> all = new ArrayList<>();
        for (FileEntry entry : inventory()) {
            String address = entry.root().label() + "/" + entry.relative().replace('\\', '/');
            if (needle.isEmpty() || address.toLowerCase(Locale.ROOT).contains(needle)) {
                all.add(address);
            }
        }
        all.sort(Comparator
            .comparingInt((String a) -> fileName(a).toLowerCase(Locale.ROOT).contains(needle) ? 0 : 1)
            .thenComparingInt(String::length)
            .thenComparing(Comparator.naturalOrder()));
        return all.size() > max ? all.subList(0, max) : all;
    }

    private static String fileName(String address) {
        int slash = address.lastIndexOf('/');
        return slash < 0 ? address : address.substring(slash + 1);
    }

    // --- internals -------------------------------------------------------------------------------

    private Path resolve(String address) {
        int slash = address.replace('\\', '/').indexOf('/');
        String label = slash < 0 ? address : address.substring(0, slash);
        String rest = slash < 0 ? "" : address.substring(slash + 1);
        for (Root root : roots) {
            if (root.label().equals(label)) {
                Path resolved = root.path().resolve(rest.replace('\\', '/')).normalize();
                boolean lexicallyInside = resolved.startsWith(root.path().toAbsolutePath().normalize())
                    || resolved.startsWith(root.path());
                // And still inside once symbolic links are followed: every address here comes
                // from a model (the expert's and the roles' read_file and list_files), and the
                // project root is a tree model-written code lands in.
                return lexicallyInside
                    && com.swarmcoder.sandbox.ConfinedPath.inside(root.path(), resolved)
                    ? resolved : null;
            }
        }
        return null;
    }

    /**
     * Time-to-live for the file inventory.
     *
     * <p>The walk is expensive enough to cache but the cache used to be permanent, so a
     * long-running orchestrator never saw a file added to the project or to a reference folder —
     * an agent would be told a class does not exist minutes after someone wrote it. A short TTL
     * keeps the walk off the hot path while bounding how stale the answer can be.
     */
    private static final long INVENTORY_TTL_MS = 60_000;
    private volatile long inventoryLoadedAt;

    /** Forces the next lookup to re-walk — for when something is known to have changed. */
    public void invalidateInventory() {
        inventoryCache = null;
        docInventoryCache = null;
        index.invalidate();
    }

    /**
     * The source files that answer {@code query}, ranked — guide-quality code first, tests last,
     * archetype skeletons and templates not at all. The Librarian shapes these to the question;
     * this method only decides WHICH.
     */
    List<ReferenceIndex.SrcHit> sourceHits(String query, int max) {
        return index.searchSources(query, max);
    }

    private List<FileEntry> inventory() {
        List<FileEntry> cached = inventoryCache;
        if (cached != null && System.currentTimeMillis() - inventoryLoadedAt < INVENTORY_TTL_MS) {
            return cached;
        }
        walkRoots();
        return inventoryCache;
    }

    /**
     * Every markdown document under the roots, documentation folders included — the channel
     * {@code conventions()} never had. Kept in its OWN list with its own cap so a repository
     * with hundreds of Java files cannot crowd the documentation out of the inventory, which
     * a single shared 400-file cap did.
     */
    private List<FileEntry> docInventory() {
        List<FileEntry> cached = docInventoryCache;
        if (cached != null && System.currentTimeMillis() - inventoryLoadedAt < INVENTORY_TTL_MS) {
            return cached;
        }
        walkRoots();
        return docInventoryCache;
    }

    private synchronized void walkRoots() {
        if (inventoryCache != null && docInventoryCache != null
            && System.currentTimeMillis() - inventoryLoadedAt < INVENTORY_TTL_MS) {
            return; // another thread just walked
        }
        List<FileEntry> entries = new ArrayList<>();
        List<FileEntry> docs = new ArrayList<>();
        for (Root root : roots) {
            if (root.path() == null || !Files.isDirectory(root.path())) {
                continue;
            }
            List<FileEntry> rootFiles = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(root.path())) {
                walk.filter(Files::isRegularFile)
                    .filter(f -> {
                        String name = f.toString().replace('\\', '/');
                        return (name.endsWith(".java") || name.endsWith(".md"))
                            && !name.contains("/target/") && !name.contains("/.git/")
                            && !name.contains("/node_modules/");
                    })
                    .sorted()
                    .forEach(f -> rootFiles.add(new FileEntry(root, f,
                        root.path().relativize(f).toString().replace('\\', '/'))));
            } catch (Exception e) {
                log.warn("Knowledge inventory walk failed for {}: {}", root.path(), e.getMessage());
            }
            entries.addAll(rootFiles);
            rootFiles.stream()
                .filter(f -> isDocumentation(f.relative()))
                .sorted(Comparator.comparingInt((FileEntry f) -> documentRank(f.relative()))
                    .thenComparing(FileEntry::relative))
                .limit(MAX_DOC_FILES_PER_ROOT)
                .forEach(docs::add);
        }
        inventoryCache = entries;
        docInventoryCache = docs;
        inventoryLoadedAt = System.currentTimeMillis();
    }

    /**
     * Written documentation: a top-level markdown file (README, AGENTS, CONTRIBUTING …) or
     * anything markdown inside a {@code docs/}, {@code doc/} or {@code documentation/} folder
     * at any depth. Deliberately not "every .md in the tree" — a large repository's markdown
     * is mostly changelogs, issue templates and vendored files.
     */
    static boolean isDocumentation(String relative) {
        if (!relative.toLowerCase(Locale.ROOT).endsWith(".md")) {
            return false;
        }
        String[] segments = relative.split("/");
        if (segments.length == 1) {
            return true; // top-level: README.md, AGENTS.md, CONTRIBUTING.md …
        }
        for (int i = 0; i < segments.length - 1; i++) {
            if (DOC_DIRS.contains(segments[i].toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Listing order: entry docs, then a docs/ root, then everything deeper. */
    private static int documentRank(String relative) {
        if (!relative.contains("/")) {
            return relative.toLowerCase(Locale.ROOT).startsWith("readme") ? 0 : 1;
        }
        return relative.chars().filter(c -> c == '/').count() == 1 ? 2 : 3;
    }

    private static Set<String> tokenize(String text) {
        Set<String> terms = new LinkedHashSet<>();
        for (String token : (text == null ? "" : text).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() > 3) {
                terms.add(token);
            }
        }
        return terms;
    }

    private static String readCapped(Path file, int maxChars) {
        try {
            String content = Files.readString(file);
            return content.length() <= maxChars ? content
                : content.substring(0, maxChars) + "\n… (truncated)";
        } catch (Exception e) {
            return "";
        }
    }
}
