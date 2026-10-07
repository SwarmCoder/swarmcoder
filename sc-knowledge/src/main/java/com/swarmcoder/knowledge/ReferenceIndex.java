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

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Everything under the knowledge roots, indexed once and searched with BM25 — the retrieval the
 * Librarian answers {@code lookup_api} from.
 *
 * <p><b>What was wrong with counting keywords.</b> Measured 2026-09-03 on a 681-file reference
 * folder. The old curator kept 400 source files per root in sorted path order, so five whole
 * modules — the framework's own API, the store wiring, every UI component — were past the cap and
 * no query could reach them at all. What it did rank, it ranked by how many of the query's words a
 * chunk repeated, which is a measure of how much a chunk talks, not of whether it answers: a file
 * of prompts for an agent that was told to BUILD the example the worker wanted to read won the
 * same question four times, and the guide that answered it was never returned once.
 *
 * <p><b>What this does instead.</b> Three things, and each is needed:
 *
 * <ol>
 *   <li><b>No cap.</b> Every source file contributes its {@link JavaOutline} — package, type
 *       names, annotations, public signatures, the first sentence of each javadoc — and every
 *       document contributes its sections. Outlines and sections are small; bodies are read on
 *       demand, so indexing everything costs a few megabytes rather than a worker's context.</li>
 *   <li><b>BM25 over fields, not term counts.</b> Rare words carry weight and common ones do not,
 *       which is exactly the difference between a page that answers a question and a page that
 *       merely mentions its subject; and a long page no longer wins for being long.</li>
 *   <li><b>Kind.</b> {@link FileKinds} says what each file IS, and a hit is multiplied by it, so a
 *       guide outranks a prompt template no matter how the words fall.</li>
 * </ol>
 *
 * <p><b>Built once, kept.</b> The index lives on disk under
 * {@code <indexRoot>/<label>-<hash of the root set>} and survives restarts. Content changes are
 * applied INCREMENTALLY: each file carries a stamp (its size and modification time), the stamps
 * are kept beside the index, and a refresh re-reads only what moved. That is what makes this
 * affordable on a million-line repository, where the project root changes under the orchestrator
 * every few seconds — rebuilding per fingerprint would rebuild continuously.
 */
final class ReferenceIndex {

    private static final Logger log = LoggerFactory.getLogger(ReferenceIndex.class);

    /** How often the roots are re-walked for changes. The walk itself reads no file contents. */
    private static final long REFRESH_TTL_MS = 60_000;
    /** A source file bigger than this is outlined from its first megabyte; nothing real is. */
    private static final int MAX_SOURCE_BYTES = 1_000_000;
    /** A document bigger than this is read to here; the largest in the measured folder is 46 KB. */
    private static final int MAX_DOC_BYTES = 1_000_000;
    /** How many raw Lucene hits are re-ranked by kind before the answer is cut. */
    private static final int CANDIDATES = 60;

    /**
     * The index's own layout and analysis, and part of its directory name — so changing WHAT is
     * indexed or HOW it is analysed builds a new index instead of searching an old one under new
     * rules, which returns nothing at all and says nothing about why.
     */
    private static final String INDEX_FORMAT = "v1-fielded-bm25";

    /**
     * Field boosts, documentation.
     *
     * <p>A section's heading is the strongest signal there is about which PART of a page answers,
     * so it is boosted hard. A page's title and file name are much weaker than they look, and they
     * were the whole defect at first: BM25 gives a rare word in a two-token file name an enormous
     * score, so every question containing the word "store" was answered by the page whose file is
     * called {@code store-modes.md} — its name, its title and one of its headings all say "store"
     * — while the guide that answers is called "Saving data", lives at {@code guides/persistence.md}
     * and says "root" twenty times, "storage" nineteen times and "storeAll" three times in its
     * prose. A file name is a hint about a page. Its prose is the page.
     */
    private static final float BOOST_HEADING = 4f;
    private static final float BOOST_TITLE = 0.8f;
    private static final float BOOST_DOC_PATH = 2f;
    private static final float BOOST_HEADINGS = 1f;
    private static final float BOOST_BODY = 1.4f;

    /**
     * How a page's own relevance and a section's are combined.
     *
     * <p><b>Two levels, because one is not enough.</b> Score sections alone and the winner is
     * whichever heading happens to repeat a word of the question: measured 2026-09-03, twelve
     * questions about saving data were answered by the first paragraph of the page titled "Store
     * modes", because "store" is in its title, its heading and its file name, while the guide that
     * actually answers is called "Saving data" and has headings like "The basics". Score pages
     * alone and the worker gets a whole 46-KB reference page for a one-line question. So: the PAGE
     * decides which document answers — its title, its path, all of its headings and all of its
     * prose — and the SECTION decides which part of that document is shown.
     */
    private static final double PAGE_WEIGHT = 1.0;
    private static final double SECTION_WEIGHT = 0.8;
    /**
     * Field boosts, source. A file whose TYPE is named in full is the file being asked about; a
     * file that merely shares one hump of its name is not.
     *
     * <p>Those are two different fields on purpose. With one field a worker asking about a class
     * that does not exist — {@code ZeroZDbNode}, which it had invented — was answered with
     * {@code NodeHolder.java}, because "node" is a rare word and the name field is short. The full
     * name is worth a great deal; a fragment of it is worth about as much as a path segment.
     */
    private static final float BOOST_NAME = 8f;
    private static final float BOOST_NAME_PART = 3f;
    private static final float BOOST_SRC_PATH = 2f;
    private static final float BOOST_OUTLINE = 1.5f;

    /**
     * The extra multiplier a query TERM gets when it is one the worker's own question just added
     * — a word that was not in any of its earlier {@code lookup_api} questions this task.
     *
     * <p>Without this, a refined question ranks the same way a broader one did: BM25 already
     * downweights a common word, but "createDefaultRoot" and "root" both appear in the same
     * handful of pages here, so adding the specific method name to a question moved the raw score
     * by a few percent — nowhere near enough to unseat a page that answers the broad half of the
     * question and says nothing about the specific half. Multiplying just the NEW word's own field
     * boosts is a small, targeted change: it cannot invent a match that BM25 did not already find
     * (a term with zero occurrences anywhere still contributes nothing), it only reweights among
     * the pages that already matched something.
     */
    private static final float DISTINGUISHING_TERM_BOOST = 3f;

    /** Words that describe the question rather than anything that could be in an answer. */
    private static final Set<String> STOP = new java.util.HashSet<>(SourceShaper.NOISE);

    static {
        STOP.addAll(List.of("how", "configure", "create", "start", "get", "gets", "set", "sets",
            "the", "and", "for", "you", "your", "here", "does", "doing", "done", "make", "made",
            "work", "works", "used", "will", "can", "not", "any", "all", "its", "it's"));
    }

    /** One document in the catalogue. */
    record DocEntry(String address, FileKinds.Kind kind, String title, String summary, int rank) {}

    /** One matched documentation section. */
    record DocHit(String address, String heading, String body, FileKinds.Kind kind, double score) {}

    /** One matched source file. */
    record SrcHit(String address, Path file, FileKinds.Kind kind, String outline, double score) {}

    /** What the index cost to build, for the record. */
    record Stats(int files, int documents, int sections, long buildMillis, long bytesOnDisk) {}

    private final List<KnowledgeCurator.Root> roots;
    private final List<String> kindOverrides;
    private final String label;
    private final Path indexRoot;
    private final Path indexDir;
    private final Path stampsFile;
    /** Addresses that came from a library server or a documentation site, not from a folder. */
    private final Path externalsFile;

    private Directory directory;
    private StandardAnalyzer analyzer;
    private volatile DirectoryReader reader;
    private volatile IndexSearcher searcher;
    private volatile long checkedAt;
    private volatile boolean broken;
    private volatile Stats stats = new Stats(0, 0, 0, 0L, 0L);
    private volatile List<DocEntry> catalogue = List.of();

    ReferenceIndex(List<KnowledgeCurator.Root> roots, Path indexRoot, List<String> kindOverrides) {
        this.roots = roots == null ? List.of() : List.copyOf(roots);
        this.kindOverrides = kindOverrides == null ? List.of() : List.copyOf(kindOverrides);
        this.label = this.roots.isEmpty() ? "empty"
            : this.roots.get(this.roots.size() - 1).label();
        this.indexRoot = indexRoot;
        this.indexDir = indexRoot.resolve(sanitise(label) + "-" + rootSetHash());
        this.stampsFile = indexDir.resolve("stamps.txt");
        this.externalsFile = indexDir.resolve("external.txt");
    }

    // --- what the Librarian and the curator ask for ------------------------------------------

    /** Every document, guides first, meta last — the order the brief's catalogue is written in. */
    List<DocEntry> catalogue() {
        ensureCurrent();
        return catalogue;
    }

    Stats stats() {
        ensureCurrent();
        return stats;
    }

    /** Forces the next call to re-walk the roots for changes. */
    void invalidate() {
        checkedAt = 0L;
    }

    /**
     * The documentation sections that answer {@code query}, best first.
     *
     * @param referenceOnly leave out process material entirely (the prompt prefix, which is not a
     *                      search result and is paid by every worker whether it wanted it or not)
     */
    List<DocHit> searchDocs(String query, int max, boolean referenceOnly) {
        return searchDocs(query, max, referenceOnly, Set.of());
    }

    /**
     * As {@link #searchDocs(String, int, boolean)}, but {@code boostTerms} — the words in
     * {@code query} that distinguish it from questions this same worker already asked — get an
     * extra {@link #DISTINGUISHING_TERM_BOOST} on top of their ordinary field weight. Empty by
     * default, which is a no-op: every existing caller and every score this class has ever
     * produced is unchanged.
     */
    List<DocHit> searchDocs(String query, int max, boolean referenceOnly, Set<String> boostTerms) {
        Map<String, Double> pages = new HashMap<>();
        for (Scored scored : search("page", query, pageFields(), boostTerms)) {
            pages.merge(scored.document().get("file"), scored.score(), Math::max);
        }
        List<DocHit> hits = new ArrayList<>();
        for (Scored scored : search("doc", query, sectionFields(), boostTerms)) {
            Document document = scored.document();
            String file = document.get("file");
            FileKinds.Kind kind = kindOf(document);
            String body = document.get("body");
            double combined = PAGE_WEIGHT * pages.getOrDefault(file, 0.0)
                + SECTION_WEIGHT * scored.score();
            double weighted = combined * kindWeight(kind) * shelfWeight(file, kind)
                * lengthFactor(body.length());
            hits.add(new DocHit(file, document.get("heading"), body, kind, weighted));
        }
        return rank(hits, DocHit::kind, DocHit::score, DocHit::address, DocHit::heading,
            max, referenceOnly);
    }

    /** The source files that answer {@code query}, best first. Tests rank below real code. */
    List<SrcHit> searchSources(String query, int max) {
        List<SrcHit> hits = new ArrayList<>();
        for (Scored scored : search("src", query, srcFields(), Set.of())) {
            Document document = scored.document();
            FileKinds.Kind kind = kindOf(document);
            hits.add(new SrcHit(document.get("file"), Path.of(document.get("abs")), kind,
                document.get("outline"), scored.score() * kindWeight(kind)));
        }
        return rank(hits, SrcHit::kind, SrcHit::score, SrcHit::address, hit -> "", max, false);
    }

    // --- ranking -------------------------------------------------------------------------------

    /**
     * Applies the kind multiplier and the length penalty, then cuts to {@code max}.
     *
     * <p>Material that is not an answer — a prompt template, a release note, an archetype skeleton
     * — is dropped, not demoted, EXCEPT when nothing else in the whole folder matched: a worker
     * asking what changed in a release must still be able to find out. Demoting instead of
     * dropping was tried and is not enough, because on a question about the framework's own
     * subject matter a document that lists every subject at once always scores.
     */
    private <T> List<T> rank(List<T> hits,
                             java.util.function.Function<T, FileKinds.Kind> kindOf,
                             java.util.function.ToDoubleFunction<T> scoreOf,
                             java.util.function.Function<T, String> addressOf,
                             java.util.function.Function<T, String> secondaryOf,
                             int max, boolean referenceOnly) {
        List<T> answerable = hits.stream().filter(hit -> kindOf.apply(hit).isAnswerable()).toList();
        List<T> pool = answerable.isEmpty() && !referenceOnly ? hits : answerable;
        // Deterministic to the last place: the shared prompt prefix must be byte-identical for
        // every worker of a group, so nothing here may depend on walk order or segment layout.
        List<T> sorted = new ArrayList<>(pool);
        sorted.sort(Comparator.comparingDouble((T hit) -> -round(scoreOf.applyAsDouble(hit)))
            .thenComparing(addressOf)
            .thenComparing(secondaryOf));
        return sorted.size() > max ? List.copyOf(sorted.subList(0, max)) : List.copyOf(sorted);
    }

    /** Material that is not an answer scores nothing, and is only ever a last resort. */
    private static double kindWeight(FileKinds.Kind kind) {
        return kind.isAnswerable() ? kind.weight() : 0.01;
    }

    /**
     * Where a page sits in the documentation, as a multiplier: a task guide is likelier to answer
     * "how do I do X" than a walkthrough, a concepts page or a release note, and that is a fact
     * about the shelf it is on rather than about its words. Measured on the reference folder: with
     * words alone, "how do I reach the store and save" was answered by the page explaining the two
     * store MODES, by a code walkthrough's step 3, and by the reference list of LIMITATIONS —
     * every one of them a page about the subject, none of them the page that tells you what to
     * type. The guide that does is one folder deeper and never won.
     */
    private static double shelfWeight(String address, FileKinds.Kind kind) {
        return switch (FileKinds.listingRank(address, kind)) {
            case 0 -> 1.00;   // docs/guides — written to be followed
            case 1 -> 0.85;   // docs/reference — written to be looked up, not to be followed
            case 2 -> 0.82;   // a quickstart
            case 3 -> 0.78;   // a topical page at the top of the documentation folder
            case 4 -> 0.72;   // a README — a pitch with facts in it
            default -> 0.70;
        };
    }

    /** Scores are compared at four decimal places, so a float wobble cannot reorder an answer. */
    private static double round(double score) {
        return Math.round(score * 10_000d) / 10_000d;
    }

    /**
     * A section longer than it can be shown at pays in proportion to how much of it the worker
     * will never see. BM25 already normalises for length; this is the SECOND penalty, and it is
     * about the render budget rather than about relevance: a 50,000-character page cannot deliver
     * its answer through a 3,500-character window even when the answer is in there.
     */
    static double lengthFactor(int chars) {
        if (chars <= 0) {
            return 1.0;
        }
        double tooLong = 1.0
            / (1.0 + Math.log(Math.max(1.0, chars / (double) KnowledgeCurator.REFERENCE_SECTION_CHARS)));
        return chars < STUB_SECTION_CHARS ? tooLong * STUB_PENALTY : tooLong;
    }

    /**
     * Under this, a section is a page's opening blurb or a stub, not an answer. BM25 rewards a
     * short field hard, so the three-line paragraph under a page's title outscored every section
     * of that page that had anything in it — the worker was handed "Store modes: embedded or
     * server-hosted. Pick one." twelve times.
     */
    private static final int STUB_SECTION_CHARS = 400;
    private static final double STUB_PENALTY = 0.55;

    // --- searching -----------------------------------------------------------------------------

    private record Scored(Document document, double score) {}

    /** The section level: which part of a page answers. Title and path are the page's, not its. */
    private static Map<String, Float> sectionFields() {
        return Map.of("heading", BOOST_HEADING, "body", BOOST_BODY);
    }

    /** The page level: which document is about the question at all. */
    private static Map<String, Float> pageFields() {
        return Map.of("title", BOOST_TITLE, "path", BOOST_DOC_PATH,
            "headings", BOOST_HEADINGS, "body", BOOST_BODY);
    }

    private static Map<String, Float> srcFields() {
        return Map.of("name", BOOST_NAME, "namepart", BOOST_NAME_PART,
            "path", BOOST_SRC_PATH, "outline", BOOST_OUTLINE);
    }

    private List<Scored> search(String unit, String query, Map<String, Float> fields,
                                Set<String> boostTerms) {
        ensureCurrent();
        IndexSearcher current = searcher;
        List<String> terms = terms(query);
        if (current == null || terms.isEmpty()) {
            return List.of();
        }
        Set<String> distinguishing = expandBoostTerms(boostTerms);
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(new TermQuery(new Term("unit", unit)), BooleanClause.Occur.FILTER);
        BooleanQuery.Builder should = new BooleanQuery.Builder();
        for (Map.Entry<String, Float> field : new java.util.TreeMap<>(fields).entrySet()) {
            for (String term : terms) {
                float boost = field.getValue()
                    * (distinguishing.contains(term) ? DISTINGUISHING_TERM_BOOST : 1f);
                should.add(new BoostQuery(new TermQuery(new Term(field.getKey(), term)),
                    boost), BooleanClause.Occur.SHOULD);
            }
        }
        builder.add(should.build(), BooleanClause.Occur.MUST);
        List<Scored> out = new ArrayList<>();
        try {
            TopDocs top = current.search(builder.build(), CANDIDATES);
            for (ScoreDoc hit : top.scoreDocs) {
                out.add(new Scored(current.storedFields().document(hit.doc), hit.score));
            }
        } catch (Exception e) {
            log.warn("Reference search for '{}' failed: {}", query, e.getMessage());
        }
        return out;
    }

    /**
     * {@code boostTerms} — raw words the caller wants to weight, not yet split or camel-expanded
     * — normalised through the exact same {@link #terms} pipeline used for the query itself, so
     * "createDefaultRoot" here matches the "createdefaultroot"/"create"/"default"/"root" terms
     * {@link #terms(String)} produced from the query text.
     */
    private static Set<String> expandBoostTerms(Set<String> boostTerms) {
        if (boostTerms == null || boostTerms.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new java.util.HashSet<>();
        for (String raw : boostTerms) {
            normalized.addAll(terms(raw));
        }
        return normalized;
    }

    /**
     * The query's terms, lower-cased, with camel-case and hyphen parts added — so "EclipseStore"
     * finds "eclipse" and "store", "inventory-crud" finds both halves, and "storeAll" finds a page
     * that writes it as {@code storeAll()}. The same expansion runs at index time on names and
     * paths, so the two sides always agree.
     */
    static List<String> terms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        for (String raw : (query == null ? "" : query).split("[^A-Za-z0-9]+")) {
            if (raw.isEmpty()) {
                continue;
            }
            for (String part : expand(raw)) {
                if (part.length() >= 3 && !STOP.contains(part)) {
                    terms.add(part);
                }
            }
        }
        return List.copyOf(terms);
    }

    /** A token and its camel-case humps, all lower case: "DataRoot" -> data, root, dataroot. */
    private static List<String> expand(String token) {
        List<String> parts = new ArrayList<>();
        parts.add(token.toLowerCase(Locale.ROOT));
        StringBuilder hump = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean boundary = i > 0 && Character.isUpperCase(c)
                && (!Character.isUpperCase(token.charAt(i - 1))
                    || (i + 1 < token.length() && Character.isLowerCase(token.charAt(i + 1))));
            if (boundary && hump.length() > 0) {
                parts.add(hump.toString().toLowerCase(Locale.ROOT));
                hump.setLength(0);
            }
            hump.append(c);
        }
        if (hump.length() > 0 && hump.length() < token.length()) {
            parts.add(hump.toString().toLowerCase(Locale.ROOT));
        }
        return parts;
    }

    /** Path and identifier text, expanded the way {@link #terms} expands a question. */
    private static String searchable(String text) {
        StringBuilder sb = new StringBuilder();
        for (String raw : text.split("[^A-Za-z0-9]+")) {
            for (String part : expand(raw)) {
                sb.append(part).append(' ');
            }
        }
        return sb.toString();
    }

    private FileKinds.Kind kindOf(Document document) {
        try {
            return FileKinds.Kind.valueOf(document.get("kind"));
        } catch (Exception e) {
            return FileKinds.Kind.FRAMEWORK;
        }
    }

    // --- building and keeping current ----------------------------------------------------------

    /** One file as the walk sees it: where it is, what it is called, and whether it moved. */
    private record Walked(String address, Path file, String stamp, FileKinds.Kind kind) {}

    private synchronized void ensureCurrent() {
        if (broken || roots.isEmpty()) {
            return;
        }
        if (searcher != null && System.currentTimeMillis() - checkedAt < REFRESH_TTL_MS) {
            return;
        }
        long started = System.nanoTime();
        try {
            Files.createDirectories(indexDir);
            if (directory == null) {
                directory = new MMapDirectory(indexDir);
                analyzer = new StandardAnalyzer();
            }
            Map<String, Walked> walked = walk();
            Map<String, String> known = readStamps();
            List<Walked> changed = new ArrayList<>();
            for (Walked entry : walked.values()) {
                if (!entry.stamp().equals(known.get(entry.address()))) {
                    changed.add(entry);
                }
            }
            Set<String> external = readLines(externalsFile);
            List<String> removed = known.keySet().stream()
                .filter(address -> !walked.containsKey(address))
                .filter(address -> !external.contains(address))
                .sorted().toList();
            boolean fresh = known.isEmpty() || !DirectoryReader.indexExists(directory);
            if (!changed.isEmpty() || !removed.isEmpty() || fresh) {
                writeChanges(changed, removed, fresh);
                writeStamps(walked);
            }
            reopen();
            checkedAt = System.currentTimeMillis();
            if (!changed.isEmpty() || !removed.isEmpty() || fresh) {
                long millis = (System.nanoTime() - started) / 1_000_000L;
                stats = new Stats(walked.size(), countUnit("doc"), countUnit("doc"), millis, sizeOnDisk());
                log.info("Reference index {}: {} files ({} changed, {} removed) in {} ms, {} KB on disk",
                    indexDir.getFileName(), walked.size(), changed.size(), removed.size(), millis,
                    sizeOnDisk() / 1024);
            }
            catalogue = readCatalogue();
            if (!changed.isEmpty() || !removed.isEmpty() || fresh) {
                pruneOtherFingerprints();
            }
        } catch (Exception e) {
            broken = true;
            log.warn("Reference index at {} unavailable ({}); the librarian will answer from "
                + "nothing until this is fixed", indexDir, e.toString());
        }
    }

    private void writeChanges(List<Walked> changed, List<String> removed, boolean fresh)
        throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(fresh ? IndexWriterConfig.OpenMode.CREATE
            : IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            for (String address : removed) {
                writer.deleteDocuments(new Term("file", address));
            }
            for (Walked entry : changed) {
                if (!fresh) {
                    writer.deleteDocuments(new Term("file", entry.address()));
                }
                index(writer, entry);
            }
            if (fresh) {
                // One segment on a full build: BM25's collection statistics and therefore every
                // score are then identical on every machine that indexes the same tree.
                writer.forceMerge(1);
            }
            writer.commit();
        }
    }

    private void index(IndexWriter writer, Walked entry) throws IOException {
        String lower = entry.address().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) {
            indexSource(writer, entry);
        } else {
            indexDocument(writer, entry);
        }
    }

    private void indexSource(IndexWriter writer, Walked entry) throws IOException {
        String source = read(entry.file(), MAX_SOURCE_BYTES);
        if (source.isEmpty()) {
            return;
        }
        String outline = outlineOf(source);
        String file = entry.address();
        String simple = file.substring(file.lastIndexOf('/') + 1, file.length() - 5);
        Document document = new Document();
        document.add(new StringField("unit", "src", Field.Store.NO));
        document.add(new StringField("file", file, Field.Store.YES));
        document.add(new StringField("kind", entry.kind().name(), Field.Store.YES));
        document.add(new StoredField("abs", entry.file().toString()));
        document.add(new TextField("name", simple.toLowerCase(Locale.ROOT), Field.Store.NO));
        document.add(new TextField("namepart", searchable(simple), Field.Store.NO));
        document.add(new TextField("path", searchable(file), Field.Store.NO));
        document.add(new TextField("outline", outline, Field.Store.NO));
        document.add(new StoredField("outline", outline));
        writer.addDocument(document);
    }

    /**
     * What a source file contributes: its package, the names and annotations of its types, the
     * public signatures, and the first sentence of each javadoc. Never a body — that is what makes
     * indexing a million lines affordable, and the body is one read away when a worker asks.
     */
    static String outlineOf(String source) {
        JavaOutline outline = JavaOutline.of(source);
        StringBuilder sb = new StringBuilder();
        sb.append(outline.packageName).append('\n');
        for (String imported : outline.imports) {
            sb.append(imported).append('\n');
        }
        for (JavaOutline.Member type : outline.types) {
            appendMember(sb, type, true);
        }
        return sb.toString();
    }

    private static void appendMember(StringBuilder sb, JavaOutline.Member member, boolean type) {
        if (!type && !member.exposed()) {
            return;
        }
        sb.append(member.header()).append('\n');
        if (!member.summary().isBlank()) {
            sb.append(member.summary()).append('\n');
        }
        for (JavaOutline.Member child : member.children()) {
            appendMember(sb, child, child.kind() == JavaOutline.Kind.TYPE);
        }
    }

    private void indexDocument(IndexWriter writer, Walked entry) throws IOException {
        String markdown = read(entry.file(), MAX_DOC_BYTES);
        if (markdown.isBlank()) {
            return;
        }
        writeDocumentUnits(writer, entry.address(), entry.kind(), markdown, "");
    }

    /** One page unit and one unit per section — the shape every document in the index has. */
    private void writeDocumentUnits(IndexWriter writer, String file, FileKinds.Kind kind,
                                    String markdown, String sourceId) throws IOException {
        String title = titleOf(markdown, file);
        String summary = summaryOf(markdown);
        int ordinal = 0;
        String source = sourceId == null ? "" : sourceId;
        List<KnowledgeCurator.DocSection> sections = KnowledgeCurator.sectionsOf(file, markdown);
        StringBuilder headings = new StringBuilder();
        for (KnowledgeCurator.DocSection section : sections) {
            headings.append(section.heading()).append("\n");
        }
        Document page = new Document();
        page.add(new StringField("unit", "page", Field.Store.NO));
        page.add(new StringField("file", file, Field.Store.YES));
        page.add(new StringField("kind", kind.name(), Field.Store.YES));
        page.add(new StringField("source", source, Field.Store.YES));
        page.add(new TextField("title", title, Field.Store.YES));
        page.add(new TextField("headings", headings.toString(), Field.Store.NO));
        page.add(new TextField("path", searchable(file), Field.Store.NO));
        page.add(new TextField("body", markdown, Field.Store.NO));
        page.add(new StoredField("summary", summary));
        page.add(new StoredField("rank", FileKinds.listingRank(file, kind)));
        writer.addDocument(page);
        for (KnowledgeCurator.DocSection section : sections) {
            Document document = new Document();
            document.add(new StringField("unit", "doc", Field.Store.NO));
            document.add(new StringField("file", file, Field.Store.YES));
            document.add(new StringField("kind", kind.name(), Field.Store.YES));
            document.add(new StringField("source", source, Field.Store.YES));
            document.add(new TextField("heading", section.heading(), Field.Store.YES));
            document.add(new TextField("title", title, Field.Store.YES));
            document.add(new TextField("path", searchable(file), Field.Store.NO));
            document.add(new TextField("body", section.body(), Field.Store.NO));
            document.add(new StoredField("body", section.body()));
            document.add(new StoredField("summary", summary));
            document.add(new StoredField("ordinal", ordinal++));
            document.add(new StoredField("rank", FileKinds.listingRank(file, kind)));
            writer.addDocument(document);
        }
    }

    /** The catalogue, read back from the index: one entry per document, guides first. */
    private List<DocEntry> readCatalogue() {
        IndexSearcher current = searcher;
        if (current == null) {
            return List.of();
        }
        Map<String, DocEntry> byFile = new HashMap<>();
        try {
            TopDocs all = current.search(new TermQuery(new Term("unit", "page")),
                Math.max(1, current.getIndexReader().maxDoc()));
            for (ScoreDoc hit : all.scoreDocs) {
                Document document = current.storedFields().document(hit.doc);
                String file = document.get("file");
                if (byFile.containsKey(file)) {
                    continue;
                }
                FileKinds.Kind kind = kindOf(document);
                byFile.put(file, new DocEntry(file, kind, document.get("title"),
                    document.get("summary"), FileKinds.listingRank(file, kind)));
            }
        } catch (Exception e) {
            log.warn("Reference catalogue unavailable: {}", e.getMessage());
            return List.of();
        }
        return byFile.values().stream()
            .sorted(Comparator.comparingInt(DocEntry::rank).thenComparing(DocEntry::address))
            .toList();
    }

    private int countUnit(String unit) {
        IndexSearcher current = searcher;
        if (current == null) {
            return 0;
        }
        try {
            return current.count(new TermQuery(new Term("unit", unit)));
        } catch (Exception e) {
            return 0;
        }
    }

    private void reopen() throws IOException {
        DirectoryReader previous = reader;
        DirectoryReader opened = previous == null ? DirectoryReader.open(directory)
            : DirectoryReader.openIfChanged(previous);
        if (opened != null) {
            reader = opened;
            searcher = new IndexSearcher(opened);
            if (previous != null) {
                previous.close();
            }
        }
    }

    private Map<String, Walked> walk() {
        Map<String, Walked> walked = new java.util.TreeMap<>();
        for (KnowledgeCurator.Root root : roots) {
            if (root.path() == null || !Files.isDirectory(root.path())) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root.path())) {
                stream.filter(Files::isRegularFile).forEach(file -> {
                    String name = file.toString().replace('\\', '/');
                    String lower = name.toLowerCase(Locale.ROOT);
                    boolean wanted = lower.endsWith(".java") || lower.endsWith(".md")
                        || lower.endsWith(".markdown") || lower.endsWith(".adoc");
                    if (!wanted || name.contains("/target/") || name.contains("/.git/")
                        || name.contains("/node_modules/") || name.contains("/build/")) {
                        return;
                    }
                    String relative = root.path().relativize(file).toString().replace('\\', '/');
                    String address = root.label() + "/" + relative;
                    if (!lower.endsWith(".java") && !KnowledgeCurator.isDocumentation(relative)) {
                        return; // markdown scattered through a source tree is not documentation
                    }
                    walked.put(address, new Walked(address, file, stampOf(file),
                        FileKinds.of(address, kindOverrides)));
                });
            } catch (Exception e) {
                log.warn("Reference walk failed for {}: {}", root.path(), e.getMessage());
            }
        }
        return walked;
    }

    private static String stampOf(Path file) {
        try {
            return Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            return "0:0";
        }
    }

    private Map<String, String> readStamps() {
        Map<String, String> stamps = new HashMap<>();
        if (!Files.isRegularFile(stampsFile)) {
            return stamps;
        }
        try {
            for (String line : Files.readAllLines(stampsFile, StandardCharsets.UTF_8)) {
                int tab = line.lastIndexOf('\t');
                if (tab > 0) {
                    stamps.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        } catch (Exception e) {
            log.warn("Reference index stamps unreadable, rebuilding: {}", e.getMessage());
            return new HashMap<>();
        }
        return stamps;
    }

    private void writeStamps(Map<String, Walked> walked) throws IOException {
        Map<String, String> stamps = new java.util.TreeMap<>();
        Set<String> external = readLines(externalsFile);
        for (Map.Entry<String, String> known : readStamps().entrySet()) {
            if (external.contains(known.getKey())) {
                stamps.put(known.getKey(), known.getValue());
            }
        }
        for (Walked entry : walked.values()) {
            stamps.put(entry.address(), entry.stamp());
        }
        StringBuilder sb = new StringBuilder();
        stamps.forEach((address, stamp) -> sb.append(address).append('\t').append(stamp).append('\n'));
        Files.writeString(stampsFile, sb.toString(), StandardCharsets.UTF_8);
    }

    private static Set<String> readLines(Path file) {
        try {
            return Files.isRegularFile(file)
                ? new java.util.LinkedHashSet<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                : new java.util.LinkedHashSet<>();
        } catch (Exception e) {
            return new java.util.LinkedHashSet<>();
        }
    }

    /**
     * Adds a page that did not come from a folder — a library server's answer, or a page of a
     * documentation site — to the SAME index, under the same ranking.
     *
     * <p><b>Why it must be the same index.</b> Before this, documentation fetched from the library
     * server went into a separate Lucene index that stored each whole reply as ONE document, was
     * consulted only after the folder search had already failed, returned the top hit whole, and
     * did not filter by which library it came from. So the two kinds of documentation could not be
     * compared, one of them could not be beaten by a better section of the other, and two libraries
     * could answer for each other. A page is a page: it gets an address, a version, a kind, and it
     * competes.
     *
     * @param sourceId  the library id or the site's base URL — carried on every section, so an
     *                  answer always says where it came from and one source can be asked for alone
     * @param version   the library or site version, part of the address the worker sees
     * @param address   {@code context7/<library>@<version>/<page>} or the page's URL
     * @param markdown  the page, headings included
     */
    synchronized void addExternalDocument(String sourceId, String version, String address,
                                          String markdown) {
        if (address == null || address.isBlank() || markdown == null || markdown.isBlank()) {
            return;
        }
        ensureCurrent();
        if (broken || directory == null) {
            return;
        }
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            try (IndexWriter writer = new IndexWriter(directory, config)) {
                writer.deleteDocuments(new Term("file", address));
                writeDocumentUnits(writer, address, FileKinds.Kind.GUIDE, markdown, sourceId);
                writer.commit();
            }
            Set<String> external = readLines(externalsFile);
            external.add(address);
            Files.writeString(externalsFile, String.join("\n", external), StandardCharsets.UTF_8);
            Map<String, String> stamps = readStamps();
            stamps.put(address, "external:" + version + ":" + markdown.length());
            StringBuilder sb = new StringBuilder();
            new java.util.TreeMap<>(stamps).forEach((key, value) ->
                sb.append(key).append('\t').append(value).append('\n'));
            Files.writeString(stampsFile, sb.toString(), StandardCharsets.UTF_8);
            reopen();
            catalogue = readCatalogue();
        } catch (Exception e) {
            log.warn("Could not index external documentation '{}': {}", address, e.getMessage());
        }
    }

    /** Whether {@code address} is already in the index — so a page is fetched once, not per ask. */
    boolean holds(String address) {
        ensureCurrent();
        return readStamps().containsKey(address);
    }

    /**
     * Deletes the index directories of the same label built from a different fingerprint. A
     * reference checkout that is pulled every week would otherwise leave a copy of itself on disk
     * every week.
     */
    private void pruneOtherFingerprints() {
        try (Stream<Path> siblings = Files.list(indexRoot)) {
            siblings.filter(Files::isDirectory)
                .filter(dir -> dir.getFileName().toString().startsWith(sanitise(label) + "-"))
                .filter(dir -> !dir.equals(indexDir))
                .forEach(ReferenceIndex::deleteTree);
        } catch (Exception e) {
            log.debug("Could not prune old reference indexes under {}: {}", indexRoot, e.getMessage());
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> tree = Files.walk(dir)) {
            tree.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception locked) {
                    // another process has it open; it will be pruned next time
                }
            });
        } catch (Exception e) {
            log.debug("Could not delete old reference index {}: {}", dir, e.getMessage());
        }
    }

    private long sizeOnDisk() {
        try (Stream<Path> files = Files.list(indexDir)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (Exception e) {
            return 0L;
        }
    }

    private String rootSetHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (KnowledgeCurator.Root root : roots) {
                digest.update(root.label().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
                digest.update(String.valueOf(root.path()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
                // The VERSION is part of the key: a reference checkout moved to another tag, or
                // carrying uncommitted edits, gets its own index instead of quietly inheriting the
                // answers of the one before it. What is stale is then deleted, not consulted.
                digest.update(root.identity().fingerprint().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            digest.update(String.join("\n", kindOverrides).getBytes(StandardCharsets.UTF_8));
            digest.update(INDEX_FORMAT.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(Integer.toHexString((b >> 4) & 0xf)).append(Integer.toHexString(b & 0xf));
            }
            return hex.substring(0, 12);
        } catch (Exception e) {
            return "default";
        }
    }

    private static String sanitise(String label) {
        return label.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String read(Path file, int maxChars) {
        try {
            String content = Files.readString(file);
            return content.length() <= maxChars ? content : content.substring(0, maxChars);
        } catch (Exception e) {
            return "";
        }
    }

    /** A document's own title — its first {@code #} heading, else its file name. */
    static String titleOf(String markdown, String address) {
        for (String line : markdown.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("# ")) {
                String title = trimmed.substring(2).strip();
                return title.length() <= 90 ? title : title.substring(0, 90) + "…";
            }
        }
        return address.substring(address.lastIndexOf('/') + 1);
    }

    /**
     * One line saying what a document is for: its first real sentence, after the title and any
     * badge or link clutter. This is what turns a catalogue of forty file names into a catalogue
     * a worker can choose from.
     */
    static String summaryOf(String markdown) {
        boolean pastTitle = false;
        for (String line : markdown.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("# ")) {
                pastTitle = true;
                continue;
            }
            if (!pastTitle || trimmed.isEmpty() || trimmed.startsWith("#")
                || trimmed.startsWith("<") || trimmed.startsWith("[!")
                || trimmed.startsWith("|") || trimmed.startsWith("```")
                || trimmed.startsWith(">") || trimmed.startsWith("---")) {
                continue;
            }
            String plain = trimmed.replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                .replace("`", "").replace("**", "").replace("*", "").strip();
            int stop = plain.indexOf(". ");
            String sentence = stop > 20 ? plain.substring(0, stop + 1) : plain;
            if (sentence.length() < 12) {
                continue;
            }
            return sentence.length() <= 120 ? sentence : sentence.substring(0, 117) + "…";
        }
        return "";
    }
}
