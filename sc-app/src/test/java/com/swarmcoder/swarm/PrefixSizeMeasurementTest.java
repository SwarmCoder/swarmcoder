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

import com.swarmcoder.testsupport.LocalCheckouts;
import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.KnowledgeBrief;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.knowledge.Context7Client;
import com.swarmcoder.knowledge.DocsIndex;
import com.swarmcoder.domain.Project;
import com.swarmcoder.knowledge.ProjectRules;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.runtime.PromptBundle;
import com.swarmcoder.store.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * What one worker pays before it has read a line of its own history — measured, on the owner's
 * real project, against the working context this hardware actually serves.
 *
 * <p><b>Why this exists.</b> Nobody had ever measured the shared prompt prefix. The reasoning
 * about it was all about prefill TIME, which is nearly free because the prefix is byte-identical
 * across a task's workers and the server prefills it once. The cost that matters is ROOM: the
 * prefix is charged against the model's working context, it is the floor {@code HistoryTrim} can
 * never compact away, and a fat one pushes a worker into compaction sooner.
 *
 * <p><b>Not an assertion test.</b> It prints the table. It is a {@code @Test} so it can be run
 * with {@code -Dtest=PrefixSizeMeasurementTest}, and it skips itself where the owner's folders
 * are not present, so it never fails a build that has nothing to measure.
 *
 * <p><b>No model is called.</b> The documentation server is constructed hosted-with-no-key, which
 * marks itself unreachable without a single request, and the primer model is null so the primer
 * comes from its disk cache or comes back empty.
 *
 * <p><b>What it measured, 2026-09-04, after the brief started carrying a worked example.</b>
 * On {@code dev/bookshelf-demo} with {@code C:/work/zeroz4j} as its reference folder:
 *
 * <pre>
 * SEGMENT                  CHARS     TOKENS   % OF 51200
 * SYSTEM_ROLE                125         31         0.1%
 * WORKFLOW_RULES            1718        429         0.8%
 * PROJECT_CONSTRAINTS      12005       3001         5.9%
 * TASK_INSTRUCTIONS          675        168         0.3%
 * KNOWLEDGE_BRIEF          15399       3849         7.5%   (was 2152 / 538 / 1.1%)
 * SHARED PREFIX            30028       7507        14.7%   (was 16781 / 4195 / 8.2%)
 * </pre>
 *
 * <p>The brief nearly all of that growth: 14,034 characters of it is the nearest existing
 * implementation of the task's contract types, rendered whole. It replaces three channels that
 * together cost 12,500 characters and were all guesses from a keyword query — the documentation
 * slice, the distilled primer, and the speculative source channel. The measured reason for
 * spending more than they cost: given the guesses, this model wrote nothing in 467 turns across
 * three runs of a plain harness; given a task it already knew the idiom for, it was green in ten.
 *
 * <p>Two things about the number. It is a FLOOR — the prefix is what history compaction can never
 * reclaim, so at 7,507 tokens a worker keeps about 13,000 tokens of its own history after a
 * compaction to the 40% low-water mark instead of about 16,000. And the largest single segment is
 * still {@code PROJECT_CONSTRAINTS} at 3,001 tokens, which is the project's own rules, capped and
 * already dropping rules at the cap.
 */
class PrefixSizeMeasurementTest {

    /** {@code ModelQuirks.workingContextTokens}, discovered from the Spark, 2026-08. */
    private static final int WORKING_CONTEXT = 51_200;

    /** {@code HistoryTrim}'s own estimator: crude, and the one the running system uses. */
    private static final int CHARS_PER_TOKEN = 4;

    private static final Path PROJECT = LocalCheckouts.find("dev/bookshelf-demo", "swarmcoder/dev/bookshelf-demo");
    private static final Path CONTEXT = LocalCheckouts.find("zeroz4j");

    /** Config default: {@code guidelines.maxPrefixTokens} 3000, times four characters. */
    private static final int GUIDELINE_CHARS = 12_000;

    /**
     * A representative rulebook, of the size and shape a real project's ACTIVE rules actually
     * are, seeded straight into the store (see {@link ProjectRules#stateRule}) rather than read
     * from a folder. Sized to comfortably clear {@link #GUIDELINE_CHARS} so the cap still has
     * something to drop, the way a real project's rulebook does.
     */
    private static final String[] SEEDED_RULES = {
        "Persist through the repository interfaces only. A view or a REST resource must never open "
            + "a database connection or build a query of its own — every read and write goes "
            + "through BookRepository or LoanRepository, so the storage layer can change under the "
            + "application without touching a single screen. This was written after a worker went "
            + "straight to a JDBC connection from inside a Vaadin view to avoid adding a repository "
            + "method, which meant the transaction boundary the repository layer relies on for "
            + "consistent reads no longer held for that one screen, and a reader could briefly see a "
            + "book as available a moment after it had already been borrowed by someone else.",
        "Every REST endpoint returns a typed DTO, never a JPA entity. An entity carries lazy "
            + "collections and internal ids that leak the schema into the wire format the moment it "
            + "is serialized; the client module only ever sees the shapes declared in "
            + "bookshelf-demo-shared. Serializing an entity directly also couples the wire contract "
            + "to whatever the persistence provider happens to generate for a lazy proxy, so a "
            + "harmless-looking change to an association on the server side can silently break every "
            + "client that reads the response, with no compile-time warning anywhere in the client "
            + "module to catch it.",
        "Client screens use the shared design tokens for colour, spacing and type — never a literal "
            + "hex code or pixel value in a component's own stylesheet. A screen that looks right "
            + "today and drifts from the rest of the app tomorrow is a defect in the tokens, not an "
            + "excuse to bypass them. If a token is genuinely missing for a case a screen needs, the "
            + "fix is to add it to the shared catalogue and say why, so the next screen that needs "
            + "the same thing does not reinvent it — not to write a one-off value that nothing else "
            + "in the application will ever reuse or even know exists.",
        "A book's due date is always computed server-side from the loan's start date and the "
            + "configured loan period. The client displays what the server sends; it must never "
            + "compute or adjust a due date locally, because the loan period is a business rule "
            + "that can change per membership tier, and a client that recomputes it from a stale "
            + "assumption will eventually show a date that disagrees with the one the server would "
            + "enforce at renewal time, which is confusing for the reader and impossible to debug "
            + "from a bug report that only shows a screenshot of the wrong date.",
        "Every acceptance test lives under src/test/java/swarm/accept in the module whose build "
            + "actually compiles it, named for the requirement and check it proves. A test that "
            + "asserts behaviour spanning two modules belongs to the module that owns the "
            + "user-visible outcome, not the one most convenient to write. Placing it anywhere else "
            + "means the acceptance stage may not even discover it, which is a worse failure mode "
            + "than a test that runs and fails: nothing tells anybody the check was never actually "
            + "exercised at all, and a story can drift to REVIEW on the strength of a check nobody "
            + "ever ran.",
        "Validation errors surface as a typed result, never a thrown exception crossing a service "
            + "boundary. A form that rejects a book with no title must show the reader why in the "
            + "same request/response cycle; a stack trace reaching the browser is always a bug. This "
            + "was decided after a worker's fix for one validation gap threw a checked exception out "
            + "of a service method that a dozen other call sites already depended on returning "
            + "normally, which turned one screen's missing message into five other screens' silent "
            + "500 errors.",
        "New library dependencies are declared in the owning module's own build file, at a version "
            + "already managed by the parent POM where one exists. A worker must never invent a "
            + "version number, and never add a dependency to a module that does not use it. A "
            + "version pulled from memory rather than the parent's dependency management has more "
            + "than once been a version that was never actually released, which compiles locally "
            + "against a cached artifact and then fails every clean build that has to resolve it "
            + "again from the repository.",
        "The book catalogue and the loan ledger are separate aggregates with separate repositories. "
            + "A change to how a loan is recorded must never require touching how a book's own "
            + "catalogue data is stored, and the reverse. Keeping the two apart is what lets a task "
            + "that only changes lending behaviour declare a write set that never overlaps a "
            + "concurrent task changing catalogue behaviour, which is the property the planner's "
            + "disjoint-write-set rule depends on to schedule the two as one wave instead of two.",
        "Every public service method is covered by at least one acceptance test that exercises it "
            + "through the same entry point a real client uses — direct unit tests of internal "
            + "helpers do not satisfy this, because they prove the helper works and say nothing "
            + "about whether the feature does. A method can pass every unit test written against it "
            + "and still be wired to the wrong screen, called with the wrong argument order, or "
            + "never called at all from anywhere a reader can reach, and only a test that goes "
            + "through the real entry point would ever catch that.",
        "Log a line at the boundary of every service call that touches persistence: what was asked "
            + "for, and what came back, with ids and never with a person's private data such as an "
            + "email address in the clear. When a run's acceptance stage fails against real data, "
            + "this is the only record of what the service layer actually did versus what the "
            + "candidate believed it did, and without it a red check tells you nothing about which "
            + "of several plausible causes actually produced it.",
        "The shelf view's empty states are never a blank screen. A reader with no borrowed books, a "
            + "catalogue with no results for a search, and a form with no books yet added each show "
            + "one short sentence saying so, in the shared empty-state component. A blank list reads "
            + "as broken even when it is correct, and a support request about a screen that is "
            + "working exactly as designed costs the same to triage as one about a real defect.",
        "A worker may not add a new top-level module to the build to work around a write-set "
            + "restriction. If a task's write set genuinely does not fit the existing module "
            + "layout, that is a planning defect to be reported, not a build file to be invented. A "
            + "module a worker invents to route around a restriction is a module nothing downstream "
            + "of PLAN was told about, so the build layout the next task is planned against is "
            + "already wrong by the time it is read.",
        "Every screen that lists more than a handful of books paginates rather than loading the "
            + "whole catalogue into the browser at once. The catalogue is expected to grow well "
            + "past what a single page can render usefully, and a screen written against a small "
            + "seed dataset that works today will time out or freeze the browser tab once the real "
            + "catalogue is large enough, which is exactly the kind of defect that a demo dataset "
            + "never surfaces and a real one always does.",
        "A candidate's commit message names the check it claims to prove, using the same ref the "
            + "story uses (e.g. R4:C1), so a reviewer scanning the integration branch's history can "
            + "tell which commit answers for which promise without opening the diff. A commit "
            + "message that only describes the code change and never the check it was written "
            + "for makes the judge's job — deciding which candidate to keep — a matter of reading "
            + "code rather than reading the one sentence that should have made the decision obvious.",
        "A check is answered by exactly one task: the task whose own work finishes the behaviour "
            + "the check proves. When delivering a check takes several tasks — a data model, then a "
            + "server implementation, then a client screen — only the last of them claims the "
            + "check; the earlier ones are enablers the last task depends on, and claim nothing of "
            + "their own. Splitting one check across several tasks leaves every task but one with a "
            + "check and no test file, since the test author writes one file per check and a task "
            + "is verified against exactly the files it claims — seen live on a run that reached "
            + "TEST_AUTHORING with the same check claimed by a model task, a server task and a "
            + "client task, and parked because two of the three could never be proved.",
        "A member's borrowing limit is enforced at the point a loan is created, never afterward by "
            + "a background job that revokes loans already granted. A member at their limit sees "
            + "the refusal in the same request that tried to create the loan, with the reason "
            + "stated in the response, not as a loan that is silently cancelled minutes later with "
            + "no explanation the reader ever sees.",
        "Search results are ordered the same way on every page of results for the same query, even "
            + "as the underlying catalogue changes between requests. A reader paging through search "
            + "results must never see the same book twice or skip one entirely because the ordering "
            + "shifted under them mid-search; the query's sort key includes a stable tiebreaker "
            + "(the book's id) for exactly this reason, and a change that removes the tiebreaker to "
            + "simplify a query is a regression even though the query still runs and still returns "
            + "correctly-shaped rows.",
        "A worker's candidate branch never rewrites history on a ref another task depends on. Once "
            + "the acceptance-tests commit or a wave's base commit has been read by a later task or "
            + "a later wave, it is load-bearing: force-pushing over it, or amending it, invalidates "
            + "every worktree already cut from it without any of those worktrees finding out, which "
            + "produces failures that look like flaky infrastructure and are actually a broken "
            + "assumption about what \"the same commit\" means.",
        "A rule this project states from a document supersedes that document's own previous "
            + "statement rather than adding a second, differently-worded copy of the same rule "
            + "alongside it. Applying a technical document more than once must leave exactly one "
            + "rule per thing it says, worded however it is worded at the moment it was last "
            + "applied — not one rule per application, which is how a project that reads the same "
            + "eleven-rule document four times ends up with forty-four rules in force, most of them "
            + "saying the same thing in slightly different words.",
        "A member's own borrowing history is visible only to that member and to a librarian acting "
            + "on a support request, never to another member browsing the catalogue. The catalogue "
            + "screen shows whether a book is available or on loan, and never shows who has it or "
            + "when they borrowed it; a search or list endpoint that joins loan records to expose "
            + "a borrower's name to anyone other than that borrower is a privacy defect regardless "
            + "of whether any screen currently renders the leaked field, because the data is in the "
            + "response either way and any client reading it can display it.",
    };

    @TempDir
    Path scratch;

    @Test
    void measure() throws Exception {
        assumeThat(Files.isDirectory(PROJECT)).isTrue();

        String rules;
        int seededRuleCount;
        try (ArtifactStore store = new ArtifactStore(scratch.resolve("store"))) {
            // Rules are store objects now, and the file mechanism this test used to read
            // (.swarmcoder/guidelines/project under the real checkout) was retired 2026-09-02 —
            // GuidelineFolderImport reads such a folder ONCE, on a project's first open, and never
            // looks at it again, so nothing here should depend on it existing on disk either. This
            // seeds representative rules straight into the store, the way a project that has always
            // lived on the new mechanism holds them, entirely inside this test's own @TempDir.
            Project project = store.ensureProject("measure", PROJECT.toString(), List.of());
            ProjectRules projectRules = new ProjectRules(store, project.id());
            for (String body : SEEDED_RULES) {
                projectRules.stateRule(null, body, null);
            }
            seededRuleCount = SEEDED_RULES.length;
            rules = projectRules.renderActive(GUIDELINE_CHARS);
        }

        // Hosted address, no key: Context7Client marks itself unreachable on the first call
        // without sending anything. Null primer model: the primer is disk-cached or empty.
        Librarian librarian = new Librarian(
            new Context7Client("https://mcp.context7.com/mcp", null, false),
            new DocsIndex(scratch.resolve("docs-index")),
            Files.isDirectory(CONTEXT) ? List.of(CONTEXT) : List.of(),
            PROJECT, null, null, null);

        Task task = task();
        KnowledgeBrief brief = librarian.assembleBrief(PROJECT, task);
        PromptBundle bundle = SwarmDispatcher.buildBundle(task, null, brief.renderedMarkdown(),
            rules, List.of());

        System.out.println();
        System.out.println("=== WORKER PROMPT PREFIX: dev/bookshelf-demo + " + CONTEXT + " ===");
        System.out.printf("%-24s %10s %10s %11s%n", "SEGMENT", "CHARS", "TOKENS", "% OF 51200");
        for (PromptBundle.SegmentSize size : bundle.segmentTokens()) {
            print(size.kind().name(), size.chars());
        }
        System.out.println("---");
        print("SHARED PREFIX (framed)", bundle.sharedText().length());
        System.out.println("estimatedTokens() = " + bundle.estimatedTokens());
        System.out.println("sizeSummary()     = " + bundle.sizeSummary());

        System.out.println();
        System.out.println("--- what the knowledge brief chose ---");
        for (String line : brief.renderedMarkdown().split("\n")) {
            if (line.startsWith("### ") || line.startsWith("#### ")
                || line.startsWith("## Source: ")) {
                System.out.println("  " + line);
            }
        }

        System.out.println();
        System.out.println("--- the project's rules ---");
        System.out.println("rules seeded into the store for this measurement: " + seededRuleCount);
        System.out.println("rendered at the " + GUIDELINE_CHARS + "-character cap: "
            + rules.length() + " chars, " + (rules.length() / CHARS_PER_TOKEN) + " tokens");
        System.out.println("was anything dropped by the cap? "
            + rules.contains("further rule(s) not shown"));

        Path dump = Paths.get(System.getProperty("java.io.tmpdir"), "prefix-dump.txt");
        Files.writeString(dump, bundle.sharedText());
        System.out.println("full prefix written to " + dump);
        System.out.println("=== END ===");
    }

    private static void print(String label, int chars) {
        int tokens = chars / CHARS_PER_TOKEN;
        System.out.printf("%-24s %10d %10d %10.1f%%%n", label, chars, tokens,
            100.0 * tokens / WORKING_CONTEXT);
    }

    /**
     * A task of the shape the architect actually plans for this project — contracts included.
     *
     * <p>The contracts are what the worked-example channel is chosen from, so a measurement taken
     * from a task without them measures the brief a task never actually gets. Every design since
     * 2026-09-03 carries them; see {@link com.swarmcoder.domain.ApiContract}.
     */
    private static Task task() {
        Task task = new Task(UUID.randomUUID(), 1L,
            "Show the reader's borrowed books on the shelf view",
            "The shelf view must list the books the signed-in reader currently has on loan, newest "
                + "first, with the due date beside each one. Add the service call the browser makes "
                + "and render the list in the existing shelf screen. An empty list shows a short "
                + "line saying nothing is on loan.",
            Set.of("bookshelf-demo-client/src/main/java/com/bookshelf/client/ShelfView.java",
                "bookshelf-demo-shared/src/main/java/com/bookshelf/shared/LoanService.java",
                "bookshelf-demo-server/src/main/java/com/bookshelf/server/LoanServiceImpl.java"),
            Set.of(), List.of(), "src/test/java/swarm/accept", null, null,
            new SwarmPolicy(4, false, 0.1, 0.8, List.of()), TaskState.PENDING);
        task.setDeliveredContracts(List.of(
            new ApiContract(UUID.randomUUID(), "Loan",
                "One book a reader currently has out.", "",
                "com.swarmcoder.demo.bookshelf.model.Loan",
                List.of("long id", "String title", "String dueDate")),
            new ApiContract(UUID.randomUUID(), "LoanService",
                "What the browser calls to read the reader's loans.", "",
                "com.swarmcoder.demo.bookshelf.api.LoanService",
                List.of("List<Loan> list()")),
            new ApiContract(UUID.randomUUID(), "LoanServiceImpl",
                "The server side of the loan list.", "",
                "com.swarmcoder.demo.bookshelf.server.LoanServiceImpl",
                List.of("List<Loan> list()"))));
        return task;
    }
}
