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
import com.swarmcoder.runtime.AgentRuntime.ToolBinding;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.ExpertHelp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the expert may look up before it answers — the whole difference between the expert as it
 * was and the expert as it is.
 *
 * <p><b>Why.</b> Until now the escalation was one chat call: the worker's question, what it tried,
 * and whatever the desk's free tiers had scraped together, pasted into a prompt. Everything the
 * expert did not happen to be handed, it had to invent — and a model inventing an API signature is
 * the exact failure the whole help desk exists to prevent. A worker gets to read this codebase
 * before it writes; the expert did not. Now it does: every question it can put to the compiler,
 * the reference index and the reference folders, it puts to them, and only then answers.
 *
 * <p><b>Every tool here is read-only.</b> Nothing writes a file, runs a command, opens a socket or
 * changes anything at all. That is not a policy that could be relaxed later — it is what makes it
 * safe to hand a paid model a tool loop over the operator's machine at all. The two tools that
 * touch the filesystem, {@code list_files} and {@code read_file}, are confined to the project
 * repository and the configured reference roots and refuse anything else by address, before any
 * read is attempted ({@link #resolveInsideRoots}).
 *
 * <p><b>Every call is recorded</b> ({@link #used()}), in order, so the worker's help record can say
 * "answered by the expert after 3 turns and 7 lookups" rather than "a model said so". The session
 * trace records them too, through the runtime's own {@code TraceHub}, so an operator can read the
 * expert's whole investigation afterwards.
 *
 * <p><b>Bounded output.</b> Every answer is capped. The expert's conversation lives inside one
 * model's working context, and a single unbounded file read is how that context is spent in one
 * turn — the same lesson {@code Librarian.lookupApi} learned about worker lookups.
 */
public final class ExpertTools {

    private static final Logger log = LoggerFactory.getLogger(ExpertTools.class);

    /**
     * How much any one lookup may return. Two ordinary methods with their imports, or so.
     *
     * <p>At the baseline room (2026-09-25). Scaled by the EXPERT's own room, which is the model
     * whose working context a tool result lands in — see {@code MaterialBudget}. On the generic
     * 32,768-token cloud shape the utility role runs on, that is exactly this figure; the harness,
     * whose expert runs on the workers' own DeepSeek endpoint, gets 5.12 times it. {@link #MAX_REFS}
     * is a count and does not move.
     */
    static final int MAX_TOOL_CHARS = 6_000;

    /** How many refs one structural query lists. A model reads the first few; the rest are noise. */
    private static final int MAX_REFS = 12;

    /**
     * How deep one {@code list_files} tree goes, a joined chain of single folders counting as one
     * level: from a root, module -> {@code src/} -> {@code main/java/com/.../store/} -> its files.
     * Harness run 37 (2026-09-25) walked that path one folder per call.
     */
    static final int TREE_DEPTH = 4;

    /** How many lines one tree may have, over all its levels, before its deepest level is cut. */
    static final int TREE_ENTRIES = 200;

    /**
     * From how many turns before the end every lookup's result carries the count of turns left.
     *
     * <p>Harness run 37 (2026-09-25): the expert read a store test with real code at its seventh
     * lookup and the persistence guide at its fourteenth, kept looking for thirty-one turns, and
     * was stopped with nothing said - nobody had ever told it the turns were running out. A worker
     * is steered at 8 and 16 investigation calls; the analyst is told to answer with what it has
     * after its last search round; the expert had no clock at all.
     */
    static final int WARN_TURNS = 5;

    /** A documentation section's heading line, as {@code KnowledgeCurator} renders it. */
    private static final Pattern SECTION_LINE = Pattern.compile("(?m)^####\\s+(.+)$");

    private final KnowledgeCurator curator;
    /** Nullable — {@code lookup_docs} and {@code library_docs} are absent without one. */
    private final Librarian librarian;
    /** Nullable — the free half of the desk's skeleton generator; {@code skeleton_for} needs it. */
    private final ExpertDesk skeletons;
    /** Consulted before every lookup: a tool must not run on a budget that is already spent. */
    private final CloudGate cloudGate;
    /** {@link #MAX_TOOL_CHARS} for the expert this toolbox serves, and every lookup's own size. */
    private final MaterialBudget room;
    private final int maxToolChars;

    private final List<String> used = Collections.synchronizedList(new ArrayList<>());

    /** Every lookup with what it returned, in order - what a forced answer is written from. */
    private final List<Found> lookups = Collections.synchronizedList(new ArrayList<>());

    /**
     * The session's turn allowance, for the countdown carried on lookup results; 0 is no
     * countdown (a toolbox built only to name its tools, or a test of one tool).
     */
    private final int turnAllowance;
    /** The turn the session is on, as the expert's turn guard last reported it. */
    private final AtomicInteger turn = new AtomicInteger();
    /** The last turn a countdown note was attached on - one note per turn, however many calls. */
    private final AtomicInteger steeredTurn = new AtomicInteger();
    /** A countdown note attached but not yet shown in the trace; see {@link #takeSteering()}. */
    private final AtomicReference<String> steering = new AtomicReference<>();

    /** Documentation sections {@code lookup_docs} has already handed this session. */
    private final Set<String> givenSections = ConcurrentHashMap.newKeySet();
    /**
     * Files a search of this session has already quoted, so a later search names them instead of
     * quoting them again ({@link ExpertSearch#search(KnowledgeCurator, String, int, boolean, Set)}).
     */
    private final Set<String> quotedFiles = ConcurrentHashMap.newKeySet();

    /**
     * How often the expert may make the very same call - same tool, same argument - in one
     * session before it is told the result instead of being given it again (2026-10-04). Twice,
     * not once: an old result is cut to its first lines as the session goes on, and reading it
     * once more is how the expert gets it back. Harness run 77: 96 of 552 lookups repeated an
     * earlier one exactly. A role's own figure is {@link User#maxRepeats}.
     */
    static final int EXPERT_MAX_REPEATS = 2;

    /** Words this session has already put to {@code lookup_docs}. */
    private final Set<String> lookupWordsSeen = ConcurrentHashMap.newKeySet();

    /**
     * One lookup and what it returned, exactly as the expert saw it.
     *
     * @param foundSomething false for a refusal, an error or a "nothing matched" - a result that
     *                       carries no material an answer could be written from
     */
    record Found(String tool, String argument, String result, boolean foundSomething) {}

    public ExpertTools(KnowledgeCurator curator, Librarian librarian, ExpertDesk skeletons,
                       CloudGate cloudGate) {
        this(curator, librarian, skeletons, cloudGate, MaterialBudget.BASELINE);
    }

    /** @param room the expert model's own working room; null is the baseline */
    public ExpertTools(KnowledgeCurator curator, Librarian librarian, ExpertDesk skeletons,
                       CloudGate cloudGate, MaterialBudget room) {
        this(curator, librarian, skeletons, cloudGate, room, 0);
    }

    /**
     * @param turnAllowance the session's turn cap, so each lookup's result can say how many turns
     *                      are left as they run out; 0 is no countdown
     */
    public ExpertTools(KnowledgeCurator curator, Librarian librarian, ExpertDesk skeletons,
                       CloudGate cloudGate, MaterialBudget room, int turnAllowance) {
        this(curator, librarian, skeletons, cloudGate, room, turnAllowance, null);
    }

    /**
     * The same lookups for a role that is not the expert (2026-10-02: the architect, the planner
     * and the test author look things up too - see {@link LookupAgent}).
     *
     * @param user who is looking things up, which changes only the words: the log line's first
     *             word, the countdown's advice, and how many lookups the session may make. Null is
     *             the expert, exactly as before.
     */
    public ExpertTools(KnowledgeCurator curator, Librarian librarian, ExpertDesk skeletons,
                       CloudGate cloudGate, MaterialBudget room, int turnAllowance, User user) {
        this.curator = curator;
        this.librarian = librarian;
        this.skeletons = skeletons;
        this.cloudGate = cloudGate;
        this.room = room == null ? MaterialBudget.BASELINE : room;
        this.maxToolChars = this.room.chars(MAX_TOOL_CHARS);
        this.turnAllowance = Math.max(0, turnAllowance);
        this.user = user;
    }

    /**
     * Who a toolbox serves when it is not the expert.
     *
     * @param name            the first word of every log line, e.g. "architect"
     * @param finishAdvice    what the countdown tells the role to do as its turns run out - one
     *                        sentence naming its own way of handing in
     * @param lookupAllowance a SAFETY STOP, not a budget: how many single-fact lookups the
     *                        session may make before every further one is refused, so a session
     *                        that has lost the thread cannot look things up for ever. Questions to
     *                        the expert and to the librarian are not counted against it. 0 is no
     *                        limit.
     * @param expert          the help desk {@code ask_expert} asks; null offers no such tool
     * @param expertQuestions a safety stop on expert questions; 0 is no limit
     * @param maxRepeats      how often the very same call - same tool, same argument - may be
     *                        made before it is refused as a loop; 0 is no limit
     */
    public record User(String name, String finishAdvice, int lookupAllowance,
                       ExpertHelp expert, int expertQuestions, int maxRepeats) {

        public User(String name, String finishAdvice, int lookupAllowance) {
            this(name, finishAdvice, lookupAllowance, null, 0, 0);
        }
    }

    /** Expert questions this session has asked, against {@link User#expertQuestions}. */
    private final AtomicInteger expertAsked = new AtomicInteger();
    /** How often each call (tool and argument) has been made, for the loop stop. */
    private final java.util.Map<String, Integer> timesAsked = new ConcurrentHashMap<>();

    /** What a call is, for the safety stops: only a single-fact lookup counts as a lookup. */
    private enum Kind { LOOKUP, QUESTION, OWN }

    /** What the documentation lookup is called in this session: the expert's name for it, or a role's. */
    private String docsTool() {
        return user == null ? "lookup_docs" : "docs_for";
    }

    /** Null for the expert. */
    private final User user;
    /** Lookups made so far, against {@link User#lookupAllowance}; a role's own tools do not count. */
    private final AtomicInteger lookupsMade = new AtomicInteger();

    /** The session is on this turn - called by the expert's turn guard before the turn's tools run. */
    void onTurn(int turnIndex) {
        turn.set(turnIndex);
    }

    /**
     * The countdown note the latest lookup result carried, once - for the session trace, so the
     * run shows a NUDGE where it happened rather than inside a tool result.
     */
    Optional<String> takeSteering() {
        return Optional.ofNullable(steering.getAndSet(null));
    }

    /** Every lookup this session made with what it returned, in order. */
    List<Found> lookups() {
        synchronized (lookups) {
            return List.copyOf(lookups);
        }
    }

    /**
     * What a lookup of this session returned, as the role saw it, for a role's own tool that
     * keeps part of a result (section 73: the architect's findings are lines of its lookups,
     * copied here and never typed again by the model).
     *
     * @param call the lookup as it was made: the tool's name, a space, its argument. The
     *             argument alone is taken too. The latest call that matches and found something
     *             answers; a call with several arguments is matched by its first.
     * @return the call as recorded ({@code tool argument}) and its result; empty when no call of
     *         this session that found something matches
     */
    public Optional<java.util.Map.Entry<String, String>> resultOf(String call) {
        if (call == null || call.isBlank()) {
            return Optional.empty();
        }
        String asked = call.strip();
        int space = asked.indexOf(' ');
        String tool = space < 0 ? "" : asked.substring(0, space);
        String argument = space < 0 ? asked : asked.substring(space + 1).strip();
        List<Found> all = lookups();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = all.size() - 1; i >= 0; i--) {
                Found found = all.get(i);
                if (!found.foundSomething() || found.argument() == null
                        || ownTools.contains(found.tool())) {
                    continue;
                }
                String recorded = found.argument().strip();
                boolean matches = pass == 0
                    ? found.tool().equals(tool) && (recorded.equals(argument)
                        || recorded.startsWith(argument + " ["))
                    : recorded.equals(asked);
                if (matches) {
                    return Optional.of(java.util.Map.entry(found.tool() + " " + recorded,
                        found.result()));
                }
            }
        }
        return Optional.empty();
    }

    /** The names of the role's own tools that ran in this session: they are not lookups. */
    private final Set<String> ownTools = ConcurrentHashMap.newKeySet();

    /** The latest lookups of this session that found something, as {@code tool argument}. */
    public List<String> callsThatFound(int max) {
        List<Found> all = lookups();
        List<String> calls = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && calls.size() < max; i--) {
            Found found = all.get(i);
            if (found.foundSomething() && found.argument() != null
                    && !ownTools.contains(found.tool())) {
                String call = found.tool() + " " + found.argument().strip();
                if (!calls.contains(call)) {
                    calls.add(call);
                }
            }
        }
        return calls;
    }

    /** Every lookup this expert made, in order — a name repeats when it was called twice. */
    public List<String> used() {
        synchronized (used) {
            return List.copyOf(used);
        }
    }

    // -------------------------------------------------------------------------------------------
    // The structural tools — the semantic index, which is the compiler's own answer
    // -------------------------------------------------------------------------------------------

    public String findImplementations(String type) {
        String fromServer = language().implementationsOrNull(type);
        if (fromServer != null) {
            return fromTheServer("find_implementations", type, fromServer);
        }
        return withIndex("find_implementations", type, index -> {
            String known = knownName(index, type);
            return renamed(type, known, index) + refs("What implements or extends `" + known + "`",
                index.implementationsOf(known));
        });
    }

    public String findUsages(String typeOrMethod) {
        String fromServer = language().usagesOrNull(typeOrMethod);
        if (fromServer != null) {
            return fromTheServer("find_usages", typeOrMethod, fromServer);
        }
        return withIndex("find_usages", typeOrMethod, index -> {
            String known = knownName(index, typeOrMethod);
            return renamed(typeOrMethod, known, index)
                + refs("Where `" + known + "` is used, and what it is used on",
                    index.usagesOf(known));
        });
    }

    /**
     * The name the index knows for what was asked about: the name as given, or - when it was
     * given with a package the index does not hold - the simple name, when the index knows that.
     *
     * <p>Harness run 66 (2026-10-02): the expert asked {@code find_implementations} about ten
     * fully-qualified names and was told "nothing in this project's material" for eight of them,
     * and {@code public_shape} "holds no type" for four of six. It was guessing the package. The
     * simple name was right and the index knew it; a wrong package is a spelling slip, not an
     * absence, and answering it as an absence is what sent the expert to walk folders instead.
     */
    static String knownName(SemanticIndex index, String asked) {
        if (asked == null) {
            return "";
        }
        String name = asked.strip();
        String member = "";
        int hash = name.indexOf('#');
        if (hash > 0) {
            member = name.substring(hash);
            name = name.substring(0, hash);
        }
        if (!name.contains(".") || !index.resolve(name).isEmpty()) {
            return name + member;
        }
        String simple = name.substring(name.lastIndexOf('.') + 1);
        return (index.resolve(simple).isEmpty() ? name : simple) + member;
    }

    /** One line saying a different name was looked up than the one asked for; "" when it was not. */
    private static String renamed(String asked, String known, SemanticIndex index) {
        if (asked == null || known.equals(asked.strip())) {
            return "";
        }
        String type = known.contains("#") ? known.substring(0, known.indexOf('#')) : known;
        return "(The index holds no `" + asked.strip() + "`. It knows `" + type + "` as "
            + String.join(", ", index.resolve(type)) + " - that is what is shown.)\n";
    }

    public String filesUsing(String types) {
        return withIndex("files_using", types, index -> {
            List<String> wanted = splitNames(types);
            if (wanted.isEmpty()) {
                return "Name at least one type, e.g. files_using(types=\"com.example.Ledger, "
                    + "com.example.Entry\").";
            }
            List<String> files = index.filesUsingAll(wanted);
            if (files.isEmpty()) {
                return "No file in this project's material uses all of " + wanted + " together.";
            }
            StringBuilder sb = new StringBuilder("Files that use all of " + wanted + ":\n");
            for (String file : files.subList(0, Math.min(MAX_REFS, files.size()))) {
                sb.append("  ").append(file).append('\n');
            }
            return sb.toString();
        });
    }

    public String typesAnnotatedWith(String annotation) {
        return withIndex("types_annotated_with", annotation, index ->
            refs("Types carrying `" + annotation + "`", index.typesAnnotatedWith(annotation)));
    }

    public String callChain(String fromMethod, String toType) {
        return withIndex("call_chain", fromMethod + " -> " + toType, index ->
            refs("How `" + fromMethod + "` reaches `" + toType + "`",
                index.callChain(fromMethod, toType)));
    }

    public String publicShape(String type) {
        return withIndex("public_shape", type, index -> {
            String known = knownName(index, type);
            String shape = index.publicShape(known);
            if (!shape.isEmpty()) {
                return renamed(type, known, index) + "`" + known
                    + "`, as the compiler resolves it:\n```java\n" + shape + "\n```\n";
            }
            // A type the tree cannot list lives in a library jar: the language server reads jars.
            String fromServer = language().membersOrNull(type == null ? "" : type.strip());
            if (fromServer != null) {
                answeredByServer.set(true);
                return fromServer;
            }
            List<SemanticIndex.Ref> uses = index.usagesOf(known);
            if (!uses.isEmpty()) {
                // Known by its import lines only: a class from a jar. Its members cannot be
                // listed, but the code that calls it can be shown, and that is the next lookup
                // the expert would have had to think of for itself.
                return "`" + known + "` (" + String.join(", ", index.resolve(known)) + ") is "
                    + "declared in a library jar, not in the material this index reads, and "
                    + (language().installed() ? "the Java language server does not find "
                        + "it in the jars this machine holds either." : language().notAvailable())
                    + " So its members cannot be listed. The code "
                    + "that uses it shows how it is called:\n"
                    + refs("Where `" + known + "` is used", uses);
            }
            return "The index holds no type called `" + type + "`"
                + (language().installed() ? ", and the Java language server finds none in the "
                    + "library jars this machine holds. Check the name, or use find_symbol with "
                    + "part of it." : ". Check the name, or use search to find what it is really "
                    + "called. (If it is a type of a library jar: " + language().notAvailable()
                    + " So a jar's types cannot be listed.)");
        });
    }

    public String dependencyDeclaring(String artifact) {
        return withIndex("dependency_declaring", artifact, index ->
            refs("Build files declaring `" + artifact + "`", index.dependencyDeclaring(artifact)));
    }

    // -------------------------------------------------------------------------------------------
    // The search - the question itself, put to everything that is indexed
    // -------------------------------------------------------------------------------------------

    /**
     * {@code search}: ranked hits across the code index, real usages, matching source and the
     * documentation, for a question in the asker's own words. See {@link ExpertSearch}.
     */
    public String search(String query) {
        // Answered with places, not with quoted files (see graphFirst): what a place holds is
        // one public_shape or body_of away.
        return run("search", query, () -> ExpertSearch.search(curator, query,
            Math.min(maxToolChars, SEARCH_CHARS), false, quotedFiles));
    }

    /**
     * The most one search answers with, whatever the room (run 80, 2026-10-04). Every other
     * lookup grows with the reader's room, and a file is read whole in a large one. A search
     * says WHERE things are and quotes its two best files; grown with a 983,040-token room it
     * answered with 7,000 to 20,000 characters, and searches were 260,000 of the 406,000
     * characters the planner and the test author looked up in that run - each resent on every
     * later call. Every address a search names is one read_file away, in full.
     * {@code -Dswarmcoder.search.maxChars} changes it.
     */
    static final int SEARCH_CHARS = Integer.getInteger("swarmcoder.search.maxChars", MAX_TOOL_CHARS);

    // -------------------------------------------------------------------------------------------
    // The documentation tools
    // -------------------------------------------------------------------------------------------

    public String lookupDocs(String query) {
        return run(docsTool(), query, Kind.QUESTION, () -> {
            if (librarian == null) {
                return "No reference index is configured for this project.";
            }
            // Sized for the expert, not for the workers the Librarian's own lookups are sized for.
            //
            // And never the same documentation section twice in one session - the worker's
            // lookup_api has done this since 2026-09-04, the expert's did not. Harness run 37
            // (2026-09-25): thirteen lookup_docs calls, most of them re-wordings of one question
            // ("EmbeddedStorageManager.Factory", "EmbeddedStorage start", "EmbeddedStorageManager
            // root store", "localDb", ...), 3,000 to 8,500 characters each, most of them after it had read
            // the persistence guide.
            String asked = query == null ? "" : query.strip();
            Set<String> newTerms = new java.util.HashSet<>(wordsOf(asked));
            newTerms.removeAll(lookupWordsSeen);
            String answer = librarian.lookupApi(asked, Set.copyOf(givenSections), newTerms, room);
            lookupWordsSeen.addAll(wordsOf(asked));
            if (answer == null || answer.isBlank()) {
                return "Nothing in this project's documentation or sources matched: " + query;
            }
            if (answer.startsWith(Librarian.NOTHING_NEW)) {
                // The worker's wording ends "or ask_expert" - which is this session.
                String sections = answer.substring(Librarian.NOTHING_NEW.length());
                int end = sections.indexOf(". There is no more");
                return NOTHING_NEW_FOR_EXPERT
                    + (end < 0 ? sections : sections.substring(0, end))
                    + ". Asking again in other words will not find more. Answer from what you have "
                    + "read (read_file any of those addresses again if you need its text).";
            }
            Matcher sections = SECTION_LINE.matcher(answer);
            while (sections.find()) {
                givenSections.add(sections.group(1).strip());
            }
            return answer;
        });
    }

    /** How {@code lookup_docs} begins when every matching section was already handed over. */
    static final String NOTHING_NEW_FOR_EXPERT = "Nothing new: every documentation section matching "
        + "this is one this session has already been shown: ";

    /** Lower-cased words of at least three characters - the same shape the index matches on. */
    private static Set<String> wordsOf(String text) {
        Set<String> words = new java.util.HashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() >= 3) {
                words.add(token);
            }
        }
        return words;
    }

    public String libraryDocs(String library, String query) {
        return run("library_docs", library + ": " + query, () -> {
            if (librarian == null) {
                return "No documentation server is configured for this project.";
            }
            Optional<String> docs = librarian.fetchLibraryDocs(library, query);
            return docs.filter(text -> !text.isBlank())
                .orElse("The documentation server had nothing for " + library + " on: " + query);
        });
    }

    // -------------------------------------------------------------------------------------------
    // The file tools — confined to the repository and the reference roots
    // -------------------------------------------------------------------------------------------

    /**
     * A folder as a tree several levels deep, single-folder chains joined into one line - not one
     * level per call, which is what cost the expert fifteen of its thirty-one turns in harness run
     * 37 (2026-09-25). See {@link KnowledgeCurator#listTree}.
     */
    public String listFiles(String dir) {
        return run("list_files", dir, () -> {
            if (dir == null || dir.isBlank()) {
                return "The material you may read, by root:\n" + rootList()
                    + "\nAddress anything inside one as <root>/<relative path>.";
            }
            String address = resolveInsideRoots(dir);
            return address == null ? refusal(dir)
                : curator.listTree(address, TREE_DEPTH, TREE_ENTRIES);
        });
    }

    /**
     * A file, capped. A FOLDER given here is answered with its tree rather than "not a file", so
     * the call is not wasted: a model that reads a folder wanted to know what is in it.
     */
    public String readFile(String path) {
        return run("read_file", path, () -> {
            if (path == null || path.isBlank()) {
                return refusal(path);
            }
            // A part of the file, when one was asked for: path:LINE, path:FROM-TO, path#member.
            String file = path.strip();
            int from = 0;
            int to = 0;
            String member = null;
            Matcher part = PART_OF_FILE.matcher(file);
            if (part.matches() && !part.group(1).isBlank()) {
                file = part.group(1);
                if (part.group(4) != null) {
                    member = part.group(4);
                } else {
                    from = number(part.group(2));
                    to = part.group(3) == null ? 0 : number(part.group(3));
                }
            }
            String address = resolveInsideRoots(file);
            if (address == null) {
                return refusal(path);
            }
            if (curator.isFolder(address)) {
                return "`" + path + "` is a folder, not a file. What is in it:\n"
                    + curator.listTree(address, TREE_DEPTH, TREE_ENTRIES);
            }
            String whole = curator.readFile(address, WHOLE_FILE_CHARS);
            if (whole.startsWith("error:")) {
                return whole;
            }
            if (member != null) {
                return memberOf(address, whole, member);
            }
            if (from > 0) {
                return linesOf(address, whole, from, to);
            }
            if (whole.length() <= maxToolChars) {
                // Never refused and never cut for this: the file is the answer. The note says,
                // from the file itself, which query returns the part of it (run 82).
                return whole + new DocumentQueries(curator).wholeFileNote(address, whole)
                    + TreeQueries.wholeFileNote(address, whole, "public_shape");
            }
            // Cut on a line, and say how to get the rest - a cut with no way on is what makes a
            // session read the same file again hoping for more.
            int cut = whole.lastIndexOf('\n', maxToolChars - PART_NOTE_CHARS);
            String shownText = whole.substring(0, cut > 0 ? cut : maxToolChars - PART_NOTE_CHARS);
            int shownLines = shownText.split("\n", -1).length;
            return shownText + "\n[Lines 1-" + shownLines + " of " + whole.split("\n", -1).length
                + ". Read more of it with " + address + ":" + (shownLines + 1) + "-"
                + (shownLines + 200) + ", or one method with " + address + "#methodName.]";
        });
    }

    /** {@code path:LINE}, {@code path:FROM-TO} or {@code path#member}, as {@code read_file} takes it. */
    private static final Pattern PART_OF_FILE = Pattern.compile(
        "^(.*?)(?::(\\d{1,7})(?:-(\\d{1,7}))?|#([A-Za-z_$][\\w$]*)(?:\\(.*\\))?)$");

    /** The most of one file that is ever loaded to take a part out of. */
    private static final int WHOLE_FILE_CHARS = 2_000_000;
    /** Room kept at the end of a cut read for the line saying how to read the rest. */
    private static final int PART_NOTE_CHARS = 300;
    /** Lines shown before and after a single line asked for by number. */
    static final int LINES_BEFORE = 12;
    static final int LINES_AFTER = 48;

    private static int number(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {                                // noqa
            return 0;
        }
    }

    /** Lines {@code from} to {@code to}; with no {@code to}, the lines around {@code from}. */
    private String linesOf(String address, String whole, int from, int to) {
        String[] lines = whole.split("\n", -1);
        int first = to > 0 ? from : Math.max(1, from - LINES_BEFORE);
        int last = Math.min(lines.length, to > 0 ? Math.max(to, from) : from + LINES_AFTER);
        if (first > lines.length) {
            return "`" + address + "` has " + lines.length + " lines; there is no line " + from
                + ".";
        }
        StringBuilder sb = new StringBuilder("Lines " + first + "-" + last + " of " + lines.length
            + " of `" + address + "`:\n");
        for (int i = first; i <= last; i++) {
            sb.append(lines[i - 1]).append('\n');
        }
        return sb.toString();
    }

    /** Every declaration called {@code member} in the file, each whole, with its lines. */
    private String memberOf(String address, String whole, String member) {
        JavaOutline outline;
        try {
            outline = JavaOutline.of(whole);
        } catch (Exception e) {                                            // noqa
            return "`" + address + "` could not be read as Java, so `#" + member + "` cannot be "
                + "taken out of it. Read it by lines instead: " + address + ":1-200.";
        }
        List<JavaOutline.Member> all = new ArrayList<>();
        for (JavaOutline.Member type : outline.types) {
            all.add(type);
            all.addAll(type.descendants());
        }
        StringBuilder sb = new StringBuilder();
        Set<String> names = new LinkedHashSet<>();
        for (JavaOutline.Member candidate : all) {
            names.add(candidate.name());
            if (candidate.name().equals(member)) {
                sb.append("// ").append(address).append(':').append(candidate.startLine())
                    .append('-').append(candidate.endLine()).append('\n')
                    .append(candidate.text()).append("\n\n");
            }
        }
        if (sb.isEmpty()) {
            return "Nothing in this project's material: `" + address + "` declares no `" + member
                + "`. It declares: " + String.join(", ", names) + ".";
        }
        return (outline.packageName == null || outline.packageName.isBlank() ? ""
            : "package " + outline.packageName + ";\n\n") + sb;
    }

    // -------------------------------------------------------------------------------------------
    // The skeleton tool
    // -------------------------------------------------------------------------------------------

    /**
     * {@code find_example}: one whole file of real code that uses the types the role names, from
     * the Librarian's by-use selection. The parameter names are the tool's schema.
     */
    public String findExample(String what, String kind) {
        return run("find_example", what + " [" + kind + "]", Kind.QUESTION, () -> {
            if (librarian == null) {
                return "No reference index is configured for this project.";
            }
            boolean wantTest = kind != null && kind.strip().toLowerCase(Locale.ROOT).startsWith("test");
            return librarian.findExample(what, wantTest, "the " + (user == null ? "expert" : user.name()));
        });
    }

    /**
     * {@code ask_expert}: the same help desk a worker asks - the run's answers first, then the free
     * tiers, then an expert session - for a how-to question that needs several sources read.
     *
     * <p>Not rationed (owner decision, 2026-10-02): a role asks as often as it needs to get the
     * right information, and a question does not count against its lookups. What is left is a
     * high ceiling against a session that has lost the thread, said in the log when it trips. Every
     * question's duration is logged, and its model calls are on the run's cost record under
     * "expert for (role)".
     */
    public String askExpert(String question) {
        return run("ask_expert", question, Kind.QUESTION, () -> {
            if (user == null || user.expert() == null) {
                return "No expert is configured for this project. Use the lookup tools.";
            }
            if (question == null || question.isBlank()) {
                return "error: no question was given.";
            }
            if (expertAsked.incrementAndGet() > user.expertQuestions()
                    && user.expertQuestions() > 0) {
                log.warn("SAFETY STOP: the {} has asked the expert {} times in one session, the "
                    + "ceiling; this question was not asked: {}", user.name(),
                    user.expertQuestions(), forTheLog(question));
                return "error: you have asked the expert " + user.expertQuestions() + " times in "
                    + "this session, which is its safety ceiling, so this question was not asked. "
                    + "Work from what you have been told.";
            }
            long started = System.currentTimeMillis();
            // Already being answered, when this question came in a turn with others: see upcoming.
            java.util.concurrent.CompletableFuture<ExpertHelp.Answer> early =
                askedEarly.remove(question);
            ExpertHelp.Answer answer = early != null ? early.join()
                : ExpertEscalation.askedBy(user.name(), () -> user.expert().askExpert(question, ""));
            long seconds = (System.currentTimeMillis() - started) / 1000;
            log.info("{} ask_expert took {}s and was answered {}{} (question {} of this session)",
                user.name(), seconds, answer.source(),
                answer.reason().isBlank() ? "" : " - " + answer.reason(), expertAsked.get());
            return answer.answered() ? answer.text()
                : "The expert had no answer: " + answer.text();
        });
    }

    /** Questions of the current turn that were put to the expert before their own call ran. */
    private final java.util.Map<String, java.util.concurrent.CompletableFuture<ExpertHelp.Answer>>
        askedEarly = new ConcurrentHashMap<>();

    /**
     * A turn is about to make several calls and this is one of them
     * ({@code AgentRuntime.SessionOptions#upcoming}). A question to the expert is put to it NOW,
     * on a thread of its own, so that two questions asked in one turn are answered side by side
     * instead of the second waiting minutes for the first (2026-10-02). The call itself still
     * runs in its turn and takes the answer from here. Everything else is ignored: every other
     * tool answers at once.
     */
    public void upcoming(String tool, String argsJson) {
        if (!"ask_expert".equals(tool) || user == null || user.expert() == null) {
            return;
        }
        if (user.expertQuestions() > 0 && expertAsked.get() >= user.expertQuestions()) {
            return; // the safety ceiling will refuse it; do not ask what will not be used
        }
        String question;
        try {
            question = new com.fasterxml.jackson.databind.ObjectMapper().readTree(argsJson)
                .path("question").asText("");
        } catch (Exception e) {                                            // noqa
            return;
        }
        if (question.isBlank() || askedEarly.containsKey(question)) {
            return;
        }
        java.util.concurrent.CompletableFuture<ExpertHelp.Answer> answer =
            new java.util.concurrent.CompletableFuture<>();
        if (askedEarly.putIfAbsent(question, answer) != null) {
            return;
        }
        String asking = question;
        Thread.ofVirtual().name("expert-question").start(() -> {
            try {
                answer.complete(ExpertEscalation.askedBy(user.name(),
                    () -> user.expert().askExpert(asking, "")));
            } catch (Throwable e) {                                        // noqa
                answer.complete(ExpertHelp.Answer.none("The expert could not be reached: " + e));
            }
        });
    }

    public String skeletonFor(String type) {
        return run("skeleton_for", type, () -> {
            if (skeletons == null) {
                return "No skeleton generator is configured for this project.";
            }
            ExpertHelp.Answer answer = skeletons.skeletonWithoutEscalating(type);
            return answer.answered() ? answer.text()
                : "Nothing in this project's contracts or reference material resembles `" + type
                    + "` closely enough to build a starting point from.";
        });
    }

    // -------------------------------------------------------------------------------------------
    // The stop signal
    // -------------------------------------------------------------------------------------------

    /**
     * The answer the worker is given, and the end of the expert's session.
     *
     * <p>Registered under {@code KoogAgentRuntime.DONE_TOOL}, so the loop stops here and returns
     * this text. The parameter NAME is the tool schema (the build compiles with {@code
     * -parameters}) — do not rename it.
     */
    public String reportDone(String answer) {
        return answer == null ? "" : answer;
    }

    // -------------------------------------------------------------------------------------------

    /**
     * The tool list the expert is opened with.
     *
     * <p>{@code library_docs} is in it only when a documentation-server key is configured, because
     * a tool that can only ever answer "nothing is configured" costs a turn to discover that.
     * Everything else is always present: each degrades to a plain sentence rather than an error
     * when the material behind it is thin, which is a thing the model can act on.
     */
    public List<ToolBinding> bindings() {
        return bindings(true);
    }

    /**
     * The lookups alone, without the expert's {@code report_done} - for a role that hands in
     * through a tool of its own (see {@link LookupAgent}).
     */
    public List<ToolBinding> lookupBindings() {
        return bindings(false);
    }

    private List<ToolBinding> bindings(boolean withReportDone) {
        try {
            List<ToolBinding> tools = new ArrayList<>(List.of(
                new ToolBinding("search",
                    "START HERE. Search everything that is indexed - the code, where things are "
                        + "used, and the documentation - for a question in your own words or for "
                        + "any names (\"TestServer beans TempDir\", \"how is the root object "
                        + "created\"). One call returns the named types with their signatures, "
                        + "the files that use them, the best matching code and the best matching "
                        + "documentation, each with an address read_file takes.",
                    this, method("search", String.class)),
                new ToolBinding("find_implementations",
                    "What implements or extends a type, with the file and line of each. Give a "
                        + "simple or fully-qualified type name.",
                    this, method("findImplementations", String.class)),
                new ToolBinding("find_usages",
                    "Where a type or a method is actually used in this project's code, with the "
                        + "file, the line, and what it was called on. Give a type name, or "
                        + "Type#method for one method.",
                    this, method("findUsages", String.class)),
                new ToolBinding("files_using",
                    "Which files use ALL of several types together — the fastest way to find the "
                        + "one worked example that does the whole thing. Give the type names "
                        + "separated by commas.",
                    this, method("filesUsing", String.class)),
                new ToolBinding("types_annotated_with",
                    "Every type carrying an annotation, with its file and line. Give the "
                        + "annotation's name.",
                    this, method("typesAnnotatedWith", String.class)),
                new ToolBinding("call_chain",
                    "How one method reaches a type, call by call — for \"what actually happens "
                        + "when this is invoked\".",
                    this, method("callChain", String.class, String.class)),
                new ToolBinding("public_shape",
                    "A type's public members with the types the COMPILER resolved, not the ones "
                        + "its source text says. Use this before naming any method in an answer.",
                    this, method("publicShape", String.class)),
                new ToolBinding("dependency_declaring",
                    "Which build files declare a Maven artifact, and the exact block they declare "
                        + "it with — for \"which dependency do I have to add\".",
                    this, method("dependencyDeclaring", String.class)),
                new ToolBinding(docsTool(),
                    "Search this project's reference documentation AND its sources for a class, "
                        + "an API or a topic. One word works best: a class name or a topic.",
                    this, method("lookupDocs", String.class)),
                new ToolBinding("list_files",
                    "Only when search has not found the place: list a folder of the project or "
                        + "of its reference material as a tree, "
                        + TREE_DEPTH + " levels deep, with a chain of single folders such as "
                        + "src/main/java/com/example/ joined into one line - one call reaches the "
                        + "files. Address it as <root>/<relative path>; call it with an empty "
                        + "string to see the roots.",
                    this, method("listFiles", String.class)),
                new ToolBinding("read_file",
                    "Read a file of the project or of its reference material, addressed as "
                        + "<root>/<relative path> — exactly the addresses the other tools print. "
                        + "Read only the part you need: <path>:LINE for the lines around a line, "
                        + "<path>:FROM-TO for a range of lines, <path>#member for one method or "
                        + "nested type. Anything outside those roots is refused.",
                    this, method("readFile", String.class)),
                new ToolBinding("skeleton_for",
                    "A compiling starting point for a type: this task's own contract when one "
                        + "names it, otherwise the nearest existing file of that shape.",
                    this, method("skeletonFor", String.class))));
            if (librarian != null && librarian.documentationServerConfigured()) {
                tools.add(new ToolBinding("library_docs",
                    "Published documentation for a third-party library, from the documentation "
                        + "server. Use it only for a library that is NOT in this project's own "
                        + "reference material.",
                    this, method("libraryDocs", String.class, String.class)));
            }
            if (user != null && librarian != null) {
                tools.add(new ToolBinding("find_example",
                    "One whole file of real code that already does the kind of thing you are "
                        + "writing, chosen by which types it uses. In `what`, name the library and "
                        + "project types involved (class names) and a few words on what the code "
                        + "does; `kind` is \"implementation\" or \"test\".",
                    this, method("findExample", String.class, String.class)));
            }
            if (user != null && user.expert() != null) {
                tools.add(new ToolBinding("ask_expert",
                    "Ask the project's expert a HOW-TO question that needs several sources read "
                        + "and put together (\"how does a test give the server its database?\"); "
                        + "it investigates and answers with the files it read. Ask whenever you "
                        + "are not sure how something is done here - an answer is always better "
                        + "than a guess. One question per call, about one thing; say what you "
                        + "already know. It reads the same files your own lookups read, so where "
                        + "a file is or what a file says is quicker to look up yourself.",
                    this, method("askExpert", String.class)));
            }
            if (withReportDone) {
                tools.add(reportDoneBinding());
            }
            return graphFirst(tools);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Every session's tools - a role's and the expert's alike: the syntax tree and the object
     * graph first, the text search and whole files after them (owner's rule, CLAUDE.md section
     * 1, 2026-10-04 after run 80).
     *
     * <p>The planning roles were meant to learn the project from its graph of types, members and
     * relations. In run 80 the planner made 13 searches (154,176 characters) and 25 file reads
     * (67,883) against 5 {@code public_shape} calls (4,628) and 2 {@code find_usages} (351). Why:
     * {@code search} was listed first and described as "START HERE", {@code list_files} as
     * "only when search has not found the place", every search quoted two files' bodies and the
     * documentation, and two things a plan needs had no graph query at all - which types a
     * module or package holds, and what a module's build declares - so folders were listed and
     * build files read whole - and no query for the body of one method by its type's name. The
     * same tools, in the order they should be reached for, with the three missing queries.
     */
    private List<ToolBinding> graphFirst(List<ToolBinding> tools) throws NoSuchMethodException {
        List<ToolBinding> all = new ArrayList<>(tools);
        List<ToolBinding> server = languageServerTools();
        all.addAll(server);
        all.add(new ToolBinding("types_in",
            "Which types a package, a module or a folder declares, from the project's object "
                + "graph: each with its kind, how many members it has, what it extends or "
                + "implements, and its file and line. Give a package (com.example.server), a "
                + "module or a folder (app-server). Use this, not list_files, to learn what a "
                + "part of the project holds.",
            this, method("typesIn", String.class)));
        all.add(new ToolBinding("build_of",
            "What a module's build file declares - its coordinates and parent, its modules, "
                + "properties, dependencies (test-scope ones with their resolved versions) and "
                + "plugins with their configuration, executions and dependencies - without the "
                + "file's text. Give the module or folder; an empty string lists every build "
                + "file. Use this instead of reading a build file.",
            this, method("buildOf", String.class)));
        all.add(new ToolBinding("resources_of",
            "A module's resource files (beans.xml, persistence descriptors, property files, "
                + "test resources): give the module to list them with sizes; give "
                + "<module>/<path under the resources folder> for one file's content when it is "
                + "small, or its outline when it is large; add #<element, key or L10-40> for one "
                + "part. Use this instead of reading a resource file.",
            this, method("resourcesOf", String.class)));
        all.add(new ToolBinding("body_of",
            "The text of ONE declaration, taken out of the syntax tree by name, with its file "
                + "and lines: Type#method for a method (every overload, or a list of them when "
                + "they are long), Type#Type for a constructor, Type#field for a field with its "
                + "initialiser, Type#first,second for several members at once, Type alone for "
                + "the whole type as code - no comments, no imports. This is how you read code: "
                + "ask for the members you need, not for the file they are in.",
            this, method("bodyOf", String.class)));
        // Every role, not only the test author of a screen (live run 90, section 66): the
        // architect read three classes of text constants whole, 9,110 characters, to learn four
        // labels, because public_shape gives a constant's name and not its value and no query it
        // had gave the value.
        all.add(new ToolBinding("texts_of",
            "The texts a type holds - every string literal in it, by member, with its "
                + "annotations' arguments - and no code: what a screen shows, what a class of "
                + "text constants holds, what a route or a message is called. Give a type name. "
                + "Use it instead of reading the file when what you need is the wording.",
            this, method("textsOf", String.class)));
        all.add(new ToolBinding("doc_outline",
            "The project's written documents by their structure. An empty string: every "
                + "document with its sections. A document's address: each of its headings with "
                + "its number, lines and size. A folder: the documents in it. Use this before "
                + "reading any document.",
            this, method("docOutline", String.class)));
        all.add(new ToolBinding("doc_section",
            "ONE section of a document, with its file and lines: <document>#<section number "
                + "or heading>, as doc_outline lists them. This is how you read a document: the "
                + "section you need, not the file.",
            this, method("docSection", String.class)));
        all.add(new ToolBinding("doc_search",
            "Which sections of which documents are about a subject: any words. Answers with "
                + "places - document, line, section number, heading and size - not text.",
            this, method("docSearch", String.class)));
        java.util.Map<String, String> said = java.util.Map.of(
            "public_shape", "START HERE for any type you can name: its members with the types "
                + "the COMPILER resolved, what it is assignable to, and where it is declared. "
                + "A few hundred characters where the file is thousands. Use this before naming "
                + "any method, and before reading the file."
                + (server.isEmpty() ? "" : " It lists a type that exists only in a library jar "
                    + "too, with its real signatures: never name a library method you have not "
                    + "seen listed."),
            "search", "ONLY when you do NOT know the name of what you are looking for: a question "
                + "in your own words, or any names. It answers with places, not text - the "
                + "types the question names with their signatures, who uses them, and the "
                + "files and documentation sections that match, each as an address. Follow one "
                + "up with public_shape, or with read_file for the lines you need.",
            "read_file", "LAST RESORT - body_of returns one method or type, public_shape a "
                + "type's members, doc_section one section of a document. For a file that is "
                + "neither Java nor a document (a resource, a script), or lines no query "
                + "returns: <path>:LINE for the lines "
                + "around a line, <path>:FROM-TO for a range, the bare path for the whole "
                + "file, which you may always read when the whole file is what you need. "
                + "Addresses are <root>/<relative path>, exactly as the other tools print "
                + "them. Anything outside the roots is refused.",
            "list_files", "A folder of the project or of its reference material as a tree, "
                + TREE_DEPTH + " levels deep - for files that are not Java types (resources, "
                + "documents, scripts). The project map you were given and types_in already "
                + "say where the types are. An empty string lists the roots.");
        List<String> order = List.of("public_shape", "types_in", "body_of", "find_usages",
            "find_implementations", "supertypes_of", "callers_of", "callees_of", "find_symbol",
            "outline_of", "doc_of", "files_using", "types_annotated_with", "call_chain",
            "build_of", "resources_of", "texts_of", "dependency_declaring", "doc_outline", "doc_section",
            "doc_search",
            "search");
        List<ToolBinding> ordered = new ArrayList<>();
        for (String name : order) {
            all.stream().filter(t -> t.name().equals(name)).forEach(ordered::add);
        }
        all.stream().filter(t -> !order.contains(t.name())).forEach(ordered::add);
        List<ToolBinding> result = new ArrayList<>();
        for (ToolBinding tool : ordered) {
            String better = said.get(tool.name());
            result.add(better == null ? tool
                : new ToolBinding(tool.name(), better, tool.target(), tool.method()));
        }
        return List.copyOf(result);
    }

    // -------------------------------------------------------------------------------------------
    // The Java language server's own questions (2026-10-04); see LanguageQueries for which
    // source answers a question both it and the tree could.
    // -------------------------------------------------------------------------------------------

    /** Set by a lookup's body when the language server, not the tree, gave the answer. */
    private final ThreadLocal<Boolean> answeredByServer = ThreadLocal.withInitial(() -> false);

    private LanguageQueries language() {
        return LanguageQueries.of(curator);
    }

    private String fromTheServer(String tool, String argument, String answer) {
        return run(tool, argument, () -> {
            answeredByServer.set(true);
            return answer;
        });
    }

    public String supertypesOf(String type) {
        return run("supertypes_of", type, () -> language().supertypesOf(type));
    }

    public String callersOf(String method) {
        return run("callers_of", method, () -> language().callersOf(method));
    }

    public String calleesOf(String method) {
        return run("callees_of", method, () -> language().calleesOf(method));
    }

    public String findSymbol(String name) {
        return run("find_symbol", name, () -> language().findSymbol(name,
            () -> knownToTheTree(name)));
    }

    /**
     * What the tree knows of a name the language server lists no type for: the full names it
     * has for it, from a declaration or from the code that uses it. "" when it knows none.
     */
    private String knownToTheTree(String name) {
        String asked = name == null ? "" : name.strip();
        if (asked.isEmpty()) {
            return "";
        }
        SemanticIndex index;
        try {
            index = curator.semanticIndex();
        } catch (Throwable e) {                                            // noqa
            return "";
        }
        if (index == null || !index.available()) {
            return "";
        }
        List<String> full = index.resolve(asked);
        if (full.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("The language server lists no type for `" + asked
            + "`, but the project's tree knows it:\n");
        for (String one : full) {
            SemanticIndex.Ref declared = index.declarationOf(one);
            sb.append("  ").append(one).append(declared == null
                ? " - from a library jar, known by the code that uses it"
                : " - declared at " + declared.file().replace('\\', '/') + ":" + declared.line())
                .append('\n');
        }
        return sb.append("public_shape <that name> lists its members; find_usages <that name> "
            + "shows the code that calls it.\n").toString();
    }

    public String outlineOf(String file) {
        return run("outline_of", file, () -> language().outlineOf(file));
    }

    public String docOf(String symbol) {
        return run("doc_of", symbol, () -> language().docOf(symbol));
    }

    /** The language server's tools, offered only when one is installed for this project. */
    private List<ToolBinding> languageServerTools() throws NoSuchMethodException {
        if (!language().installed()) {
            return List.of();
        }
        return List.of(
            new ToolBinding("supertypes_of",
                "Everything a type extends or implements, nearest first, through library jars "
                    + "too. Give a type name.",
                this, method("supertypesOf", String.class)),
            new ToolBinding("callers_of",
                "Every call of a method in this project: the calling line and the method it is "
                    + "in. Give Type#method.",
                this, method("callersOf", String.class)),
            new ToolBinding("callees_of",
                "Every method a method calls, each with where it is declared. Give Type#method.",
                this, method("calleesOf", String.class)),
            new ToolBinding("find_symbol",
                "Types by part of their name, in the project and in its library jars: a "
                    + "fragment (Greeter) or a pattern (*Repository). Use it when you know part "
                    + "of a name, before any text search.",
                this, method("findSymbol", String.class)),
            new ToolBinding("outline_of",
                "The types and members one Java file of the project declares, each on one line "
                    + "with its line number - instead of reading the file. Give the file's "
                    + "address.",
                this, method("outlineOf", String.class)),
            new ToolBinding("doc_of",
                "One symbol's exact signature and its documentation, for a project symbol or a "
                    + "library's: Type, Type#method or Type#field.",
                this, method("docOf", String.class)));
    }

    public String typesIn(String where) {
        return run("types_in", where, () -> new TreeQueries(curator).typesIn(where));
    }

    // The project's written documents, by structure (run 82, 2026-10-04): see DocumentQueries.

    public String docOutline(String document) {
        return run("doc_outline", document, () -> new DocumentQueries(curator).outlineOf(document));
    }

    public String docSection(String section) {
        return run("doc_section", section, () -> new DocumentQueries(curator).sectionOf(section));
    }

    public String docSearch(String words) {
        return run("doc_search", words, () -> new DocumentQueries(curator).search(words));
    }

    public String buildOf(String where) {
        return run("build_of", where, () -> new TreeQueries(curator).buildOf(where));
    }

    public String resourcesOf(String where) {
        return run("resources_of", where, () -> new TreeQueries(curator).resourcesOf(where));
    }

    public String bodyOf(String what) {
        return run("body_of", what, () -> new TreeQueries(curator).bodyOf(what));
    }

    /**
     * The string literals of a type, by member (section 63): what the application shows and how
     * its screens are named, without the code that draws them. In every session's list since
     * section 66; the test author of a screen task is told what to use it for.
     */
    public String textsOf(String type) {
        return run("texts_of", type, () -> new TreeQueries(curator).textsOf(type));
    }

    /** What a lookup was answered from, for the run's record of lookups; null is not counted. */
    static com.swarmcoder.inference.LookupMeter.Kind kindOf(String tool, String argument) {
        return switch (tool) {
            case "public_shape", "types_in", "body_of", "texts_of", "build_of", "resources_of",
                 "find_usages",
                 "find_implementations", "files_using", "types_annotated_with", "call_chain",
                 "dependency_declaring", "skeleton_for" ->
                com.swarmcoder.inference.LookupMeter.Kind.TREE;
            case "supertypes_of", "callers_of", "callees_of", "find_symbol", "outline_of",
                 "doc_of" -> com.swarmcoder.inference.LookupMeter.Kind.LANGUAGE_SERVER;
            case "doc_outline", "doc_section", "doc_search" ->
                com.swarmcoder.inference.LookupMeter.Kind.DOCUMENT;
            case "search", "lookup_docs", "docs_for", "library_docs", "find_example" ->
                com.swarmcoder.inference.LookupMeter.Kind.SEARCH;
            case "check_journey" -> com.swarmcoder.inference.LookupMeter.Kind.JOURNEY_CHECK;
            case "list_files" -> com.swarmcoder.inference.LookupMeter.Kind.LISTING;
            case "read_file" -> {
                Matcher part = PART_OF_FILE.matcher(argument == null ? "" : argument.strip());
                yield part.matches() && (part.group(2) != null || part.group(4) != null)
                    ? com.swarmcoder.inference.LookupMeter.Kind.FILE_PART
                    : com.swarmcoder.inference.LookupMeter.Kind.WHOLE_FILE;
            }
            default -> null;
        };
    }

    /**
     * {@code report_done} and nothing else — the tool list of the closing turn
     * {@code ExpertEscalation} opens when the investigation ran out of turns. There is nothing to
     * look up in that turn, so nothing to look up with is offered.
     */
    public List<ToolBinding> answerOnlyBindings() {
        try {
            return List.of(reportDoneBinding());
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private ToolBinding reportDoneBinding() throws NoSuchMethodException {
        return new ToolBinding("report_done",
            "Call exactly once, with your complete answer for the worker: the worked code, "
                + "the imports it needs, and the build line if a dependency is missing.",
            this, method("reportDone", String.class));
    }

    // -------------------------------------------------------------------------------------------
    // What was found — the material a forced answer is written from
    // -------------------------------------------------------------------------------------------

    /**
     * Everything this session's lookups returned that carried material, as one block of at most
     * {@code maxChars}, each result under a heading naming the lookup that produced it.
     *
     * <p>Harness run 37 (2026-09-25): an expert that had read a store test with real code and the
     * persistence guide ran out of turns and the worker was handed NOTHING. This is what it had
     * read, so that a closing turn can answer from it — and from nothing else.
     *
     * <p><b>What is kept when it does not all fit.</b> Refusals, errors and "nothing matched"
     * results carry no material and are never kept; an identical result is kept once. The rest is
     * chosen by what an answer is written FROM: a file read, a public shape or a skeleton first
     * (that is code), then documentation, then the structural indexes' lists of places, then folder
     * listings last (they only say where things are). Within a kind, the earlier lookup first — in
     * run 37 the useful reads were early. What is chosen is then printed in the order the lookups
     * were made, so the block reads as the investigation did.
     */
    String whatWasFound(int maxChars) {
        List<Found> all = lookups();
        Set<String> seen = new java.util.HashSet<>();
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Found lookup = all.get(i);
            if (lookup.foundSomething() && seen.add(lookup.result().strip())) {
                candidates.add(i);
            }
        }
        candidates.sort(Comparator.comparingInt((Integer i) -> materialRank(all.get(i).tool()))
            .thenComparingInt(i -> i));
        java.util.Map<Integer, String> chosen = new java.util.TreeMap<>();
        int left = Math.max(0, maxChars);
        for (int i : candidates) {
            Found lookup = all.get(i);
            String block = "### " + lookup.tool() + "(" + (lookup.argument() == null ? ""
                : lookup.argument()) + ")\n" + lookup.result().strip() + "\n\n";
            if (block.length() <= left) {
                chosen.put(i, block);
                left -= block.length();
            } else if (left >= MIN_USEFUL_EXCERPT) {
                chosen.put(i, block.substring(0, left - 3) + "…\n\n");
                left = 0;
            }
        }
        return String.join("", chosen.values());
    }

    /** Below this, a cut-down result is a heading and some imports — not worth the room. */
    private static final int MIN_USEFUL_EXCERPT = 1_500;

    /** Lower is what an answer is written from; higher only says where things are. */
    private static int materialRank(String tool) {
        return switch (tool) {
            case "read_file", "public_shape", "skeleton_for", "find_example", "ask_expert",
                 "search" -> 0;
            case "lookup_docs", "docs_for", "library_docs" -> 1;
            case "list_files" -> 3;
            default -> 2;
        };
    }

    /**
     * Whether a result carries material, rather than saying that there is none. Every sentence
     * this class writes for "nothing" is listed, plus the curator's and the Librarian's.
     */
    static boolean foundSomething(String result) {
        if (result == null || result.isBlank()) {
            return false;
        }
        String text = result.strip();
        for (String nothing : NOTHING_PREFIXES) {
            if (text.startsWith(nothing)) {
                return false;
            }
        }
        return !text.contains(": nothing in this project's material.");
    }

    private static final List<String> NOTHING_PREFIXES = List.of(
        "Refused:", "error:", "That lookup failed", "The budget for this run is spent",
        "No reference index is configured", "No documentation server is configured",
        "No skeleton generator is configured", "The structural index is not",
        "Nothing in this project's", "The index holds no type", "No file in this project's material",
        "Name at least one type", "No documentation found", "No documentation matched",
        "The documentation server had nothing", NOTHING_NEW_FOR_EXPERT, Librarian.NOTHING_NEW,
        ExpertSearch.NOTHING);

    // -------------------------------------------------------------------------------------------
    // The countdown
    // -------------------------------------------------------------------------------------------

    /**
     * The note a lookup's result carries on turn {@code turn} of {@code allowance}, or null.
     *
     * <p>Harness run 37 (2026-09-25): thirty-one turns, fifty-three lookups, the answer read by the
     * fourteenth, and nothing ever said — the model had no idea its turns were finite until they
     * were gone. Three notes, each once:
     *
     * <ul>
     *   <li><b>halfway</b>: if you have already read real code that does this, answer now;</li>
     *   <li><b>the last {@link #WARN_TURNS}</b>: how many are left, every turn;</li>
     *   <li><b>one left</b>: the next turn is the last, use it for {@code report_done} — a lookup in
     *       it never comes back.</li>
     * </ul>
     *
     * <p>They ride on the tool result, as the worker's steering does: a valid tool-call/tool-result
     * sequence is what chat templates handle most reliably, and there is no other channel into a
     * running session.
     */
    static String countdownNote(int turn, int allowance) {
        if (allowance <= 0 || turn <= 0) {
            return null;
        }
        int left = allowance - turn;
        if (left <= 0) {
            return null;
        }
        if (left == 1) {
            return "Turn " + turn + " of " + allowance + ". Your NEXT turn is your LAST: in it, "
                + "call report_done with the best answer the material you have already read "
                + "supports. A lookup made in that turn never comes back to you.";
        }
        if (left <= WARN_TURNS) {
            return "Turn " + turn + " of " + allowance + ": " + left + " turns left. If what you "
                + "have read already shows real code doing what the worker asked, answer now with "
                + "report_done - an answer from that code beats no answer at all.";
        }
        if (turn == allowance / 2) {
            return "Half of your " + allowance + " turns are gone. If you have already read real "
                + "code or documentation that does what the worker asked, stop looking and answer "
                + "with report_done now; the first real example is enough.";
        }
        return null;
    }

    /**
     * {@link #countdownNote(int, int)} for a role that is not the expert: the same three moments,
     * with that role's own advice about handing in.
     */
    static String countdownNote(int turn, int allowance, String finishAdvice) {
        if (allowance <= 0 || turn <= 0) {
            return null;
        }
        int left = allowance - turn;
        String advice = finishAdvice == null ? "" : " " + finishAdvice.strip();
        if (left <= 0) {
            return null;
        }
        if (left == 1) {
            return "Turn " + turn + " of " + allowance + ". Your NEXT turn is your LAST: a lookup "
                + "made in it never comes back to you." + advice;
        }
        if (left <= WARN_TURNS) {
            return "Turn " + turn + " of " + allowance + ": " + left + " turns left." + advice;
        }
        if (turn == allowance / 2) {
            return "Half of your " + allowance + " turns are gone." + advice;
        }
        return null;
    }

    /** The countdown note for the current turn, once per turn, as a suffix for a tool result. */
    private String countdown() {
        if (turnAllowance <= 0) {
            return "";
        }
        int now = turn.get();
        if (steeredTurn.getAndSet(now) == now) {
            return "";
        }
        String note = user == null ? countdownNote(now, turnAllowance)
            : countdownNote(now, turnAllowance, user.finishAdvice());
        if (note == null) {
            return "";
        }
        steering.set(note);
        return "\n\n[" + note + "]";
    }

    /**
     * The tool names an expert over this material would be opened with — for a startup log line and
     * for the harness's ledger, both of which want to STATE what the expert can do without opening
     * a session to find out.
     */
    public static List<String> toolNames(KnowledgeCurator curator, Librarian librarian) {
        return new ExpertTools(curator, librarian, null, null).bindings().stream()
            .map(ToolBinding::name).toList();
    }

    private static Method method(String name, Class<?>... types) throws NoSuchMethodException {
        return ExpertTools.class.getMethod(name, types);
    }

    // -------------------------------------------------------------------------------------------

    /**
     * Turns anything the model wrote into a {@code <root>/<relative>} address, or refuses it.
     *
     * <p>Three forms are accepted, because all three are things the model has genuinely just been
     * shown: the address form the other tools print ({@code zeroz4j/src/main/java/…}); an absolute
     * path that happens to lie inside a root (a compiler diagnostic prints those); and a plain
     * relative path, which is resolved against each root in turn. Everything else — anything that
     * normalises outside every root, and anything that walks out with {@code ..} — is null, and the
     * caller turns that into a refusal that names what may be read instead.
     */
    String resolveInsideRoots(String path) {
        String cleaned = path.replace('\\', '/').strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        List<KnowledgeCurator.Root> roots = curator.roots();
        Path asPath;
        try {
            asPath = Path.of(cleaned);
        } catch (InvalidPathException e) {                                  // noqa
            return null;
        }
        if (asPath.isAbsolute()) {
            Path normalized = asPath.normalize();
            for (KnowledgeCurator.Root root : roots) {
                Path base = root.path() == null ? null : root.path().toAbsolutePath().normalize();
                if (base != null && normalized.startsWith(base)) {
                    String relative = base.relativize(normalized).toString().replace('\\', '/');
                    return relative.isEmpty() ? root.label() : root.label() + "/" + relative;
                }
            }
            return null;
        }
        // Lexically first, so "project/../../etc/passwd" is refused before anything touches disk.
        if (Path.of(cleaned).normalize().startsWith("..")) {
            return null;
        }
        int slash = cleaned.indexOf('/');
        String head = slash < 0 ? cleaned : cleaned.substring(0, slash);
        for (KnowledgeCurator.Root root : roots) {
            if (root.label().equals(head)) {
                return cleaned;
            }
        }
        // Not addressed by root: the model gave a path relative to some root. Take the first root
        // it actually exists under, which is what it meant.
        for (KnowledgeCurator.Root root : roots) {
            Path base = root.path() == null ? null : root.path().toAbsolutePath().normalize();
            if (base == null) {
                continue;
            }
            Path candidate = base.resolve(cleaned).normalize();
            if (candidate.startsWith(base) && Files.exists(candidate)) {
                return root.label() + "/" + cleaned;
            }
        }
        return null;
    }

    private String refusal(String path) {
        return "Refused: `" + path + "` is not inside this project's material. You may read the "
            + "project itself and its reference folders, and nothing else on this machine.\n"
            + "The roots, and how to address a file inside one (<root>/<relative path>):\n"
            + rootList();
    }

    private String rootList() {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeCurator.Root root : curator.roots()) {
            sb.append("  ").append(root.label()).append("  (").append(root.path()).append(")\n");
        }
        return sb.isEmpty() ? "  (none configured)\n" : sb.toString();
    }

    private static List<String> splitNames(String types) {
        Set<String> names = new LinkedHashSet<>();
        for (String piece : types == null ? new String[0] : types.split("[,;\\s]+")) {
            String name = piece.strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private String refs(String heading, List<SemanticIndex.Ref> found) {
        if (found.isEmpty()) {
            return heading + ": nothing in this project's material.";
        }
        StringBuilder sb = new StringBuilder(heading).append(" (")
            .append(found.size()).append(found.size() == 1 ? " place" : " places").append("):\n");
        for (SemanticIndex.Ref ref : found.subList(0, Math.min(MAX_REFS, found.size()))) {
            sb.append("  ").append(address(ref));
            if (ref.detail() != null && !ref.detail().isBlank()) {
                sb.append("  ").append(ref.detail());
            }
            sb.append('\n');
        }
        if (found.size() > MAX_REFS) {
            sb.append("  … and ").append(found.size() - MAX_REFS).append(" more\n");
        }
        sb.append("Each is a place: read_file <address as printed> returns the lines around "
            + "it, body_of <Type>#<method> the whole method it is in.\n");
        return sb.toString();
    }

    /** {@code <root>/<file>:<line>} — an address {@code read_file} takes back unchanged. */
    private static String address(SemanticIndex.Ref ref) {
        String label = ref.rootLabel() == null || ref.rootLabel().isBlank()
            ? "" : ref.rootLabel() + "/";
        return label + ref.file().replace('\\', '/') + (ref.line() > 0 ? ":" + ref.line() : "");
    }

    private interface Lookup {
        String get(SemanticIndex index);
    }

    private String withIndex(String tool, String argument, Lookup lookup) {
        return run(tool, argument, () -> {
            SemanticIndex index;
            try {
                index = curator.semanticIndex();
            } catch (Throwable e) {                                        // noqa
                return "The structural index is not usable for this project (" + e + "), so this "
                    + "question cannot be answered from the compiler. Use lookup_docs or read_file "
                    + "instead.";
            }
            if (index == null || !index.available()) {
                return "The structural index is not available for this project"
                    + (index == null ? "" : " (" + index.unavailableReason() + ")")
                    + ", so this question cannot be answered from the compiler. Use lookup_docs or "
                    + "read_file instead.";
            }
            return lookup.get(index);
        });
    }

    /**
     * One lookup: gate, record, run, cap, never throw.
     *
     * <p><b>Never throw</b> because a tool that throws inside the agent loop ends the session, and
     * a session that ends has no answer for the worker — an expert that cannot look something up
     * should say so and try another way, exactly as a person would.
     *
     * <p><b>Gate first.</b> A tool result is prompt tokens on the next turn, so a lookup made after
     * the run's cloud budget is gone is spending that is not allowed to happen. The turn charge
     * itself is in {@code ExpertEscalation}; this is the check that stops the next one.
     */
    private String run(String tool, String argument, java.util.function.Supplier<String> body) {
        return run(tool, argument, Kind.LOOKUP, body);
    }

    /**
     * One call of a tool that belongs to the role itself rather than to this toolbox - a draft
     * check, a draft compile - run exactly as a lookup is: gated on the budget, recorded, logged on
     * one line, capped, never throwing, and carrying the turn countdown. It does not count against
     * the lookup allowance; the role's own tool keeps its own count.
     */
    public String runOwnTool(String tool, String argument,
                             java.util.function.Supplier<String> body) {
        return run(tool, argument, Kind.OWN, body);
    }

    /** How many lookups this session has made - a role's own tools not counted. */
    public int lookupsMade() {
        return lookupsMade.get();
    }

    private String run(String tool, String argument, Kind kind,
                       java.util.function.Supplier<String> body) {
        synchronized (used) {
            used.add(tool);
        }
        if (kind == Kind.OWN) {
            ownTools.add(tool);
        }
        if (cloudGate != null && cloudGate.exhausted()) {
            return "The budget for this run is spent, so no more can be looked up. Answer with "
                + "what you already have and call report_done.";
        }
        // The safety stops of a role's session. Neither is a budget: a role looks up and asks
        // as much as it needs (owner decision, 2026-10-02). They stop a session that has lost
        // the thread, and the log says so when one trips.
        if (user == null && kind == Kind.LOOKUP && turnAllowance > 0) {
            int times = timesAsked.merge(tool + "\u0000" + argument, 1, Integer::sum);
            if (times > EXPERT_MAX_REPEATS) {
                log.info("expert {}('{}') was made {} times in one session, so it was not made "
                    + "again", tool, forTheLog(argument), times);
                return "You have made exactly this call " + (times - 1) + " times in this session "
                    + "and it answers the same every time, so it was not made again. Answer from "
                    + "what it told you, or look up something different." + countdown();
            }
        }
        if (user != null && kind != Kind.OWN && user.maxRepeats() > 0) {
            int times = timesAsked.merge(tool + "\u0000" + argument, 1, Integer::sum);
            if (times > user.maxRepeats()) {
                log.warn("SAFETY STOP: the {} made the same call {} times - {}('{}') - so it was "
                    + "not made again", user.name(), times, tool, forTheLog(argument));
                return "error: you have made exactly this call " + (times - 1) + " times already "
                    + "and it answers the same every time, so it was not made again. Use what it "
                    + "told you, or ask something different.";
            }
        }
        if (kind == Kind.LOOKUP && lookupsMade.incrementAndGet() > 0 && user != null
                && user.lookupAllowance() > 0 && lookupsMade.get() > user.lookupAllowance()) {
            log.warn("SAFETY STOP: the {} has made {} lookups in one session, the ceiling; "
                + "{}('{}') was not made", user.name(), user.lookupAllowance(), tool,
                forTheLog(argument));
            return "error: you have made " + user.lookupAllowance() + " lookups in this session, "
                + "which is its safety ceiling, so this one was not made."
                + (user.finishAdvice() == null ? "" : " " + user.finishAdvice().strip());
        }
        String answer;
        answeredByServer.set(false);
        try {
            answer = body.get();
        } catch (Exception e) {                                            // noqa
            log.debug("expert tool {}({}) failed: {}", tool, argument, e.toString());
            answer = "That lookup failed: " + e + ". Try a different one.";
        }
        if (answer == null) {
            answer = "";
        }
        log.info("{} {}('{}') -> {} chars", user == null ? "expert" : user.name(), tool,
            forTheLog(argument), answer.length());
        String capped = answer.length() <= maxToolChars ? answer
            : answer.substring(0, maxToolChars) + "\n…\n";
        lookups.add(new Found(tool, argument, capped, foundSomething(capped)));
        com.swarmcoder.inference.LookupMeter.record(user == null ? "expert" : user.name(),
            answeredByServer.get() ? com.swarmcoder.inference.LookupMeter.Kind.LANGUAGE_SERVER
                : kindOf(tool, argument), capped.length());
        return capped + countdown();
    }

    /**
     * The argument as the log shows it: whole up to {@value #LOG_ARGUMENT_CHARS} characters, and
     * past that its head and its TAIL.
     *
     * <p>It used to be the first 80 characters, and the end of a path is the part that says which
     * file it was. Harness run 37 (2026-09-25) logged
     * {@code read_file('project/bookshelf-demo-server/src/main/java/com/swarmcoder/demo/bookshelf/server')}
     * — exactly 80 characters — which read as a read of a FOLDER and was diagnosed as one. It was
     * not: a folder given to {@code read_file} then answered "not a file" in under 200 characters,
     * so the 1,181 characters that came back were a real file somewhere below it.
     */
    static String forTheLog(String argument) {
        if (argument == null) {
            return "";
        }
        if (argument.length() <= LOG_ARGUMENT_CHARS) {
            return argument;
        }
        return argument.substring(0, 60) + "…"
            + argument.substring(argument.length() - (LOG_ARGUMENT_CHARS - 61));
    }

    private static final int LOG_ARGUMENT_CHARS = 200;
}
