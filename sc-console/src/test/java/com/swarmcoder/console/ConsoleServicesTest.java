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
package com.swarmcoder.console;

import com.swarmcoder.console.api.TraceEventDto;
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import org.eclipse.serializer.reference.Lazy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import com.swarmcoder.domain.BrdEdge;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.console.api.ConsoleReadiness;
import com.swarmcoder.console.api.GuidelineDto;
import com.swarmcoder.console.api.NextAction;
import com.swarmcoder.console.api.InsightsDto;
import com.swarmcoder.console.api.ProjectDto;
import com.swarmcoder.console.api.ProjectList;
import com.swarmcoder.console.api.RunSummaryDto;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.ChatMessage;
import com.swarmcoder.domain.Priority;
import com.swarmcoder.domain.RequirementRelation;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.ClusterId;
import com.swarmcoder.domain.GuidelineScope;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.JudgeScore;
import com.swarmcoder.domain.KnowledgeDoc;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Provenance;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunReport;
import com.swarmcoder.domain.RunState;
import com.swarmcoder.domain.SamplingConfig;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskEdge;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.domain.WorkflowKind;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Console service logic over a real store + hub — the RMI marshaling layer is zeroz4j's. */
class ConsoleServicesTest {

    @TempDir
    Path storeDir;

    @Test
    void controlAndObserverServicesWorkOverTheRealStore() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            AtomicReference<String> intakeGoal = new AtomicReference<>();
            AtomicReference<UUID> approved = new AtomicReference<>();
            UUID startedRun = UUID.randomUUID();

            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> {
                    intakeGoal.set(goal + "|" + kind);
                    return startedRun;
                },
                approved::set,
                rejected -> { }));

            ControlServiceImpl control = new ControlServiceImpl();
            ObserverServiceImpl observer = new ObserverServiceImpl();

            // Intake + approve round-trip through the context callbacks.
            assertThat(control.submitIntake("add feature", "GREENFIELD")).isEqualTo(startedRun.toString());
            assertThat(intakeGoal.get()).isEqualTo("add feature|GREENFIELD");
            control.approveRun(startedRun.toString());
            assertThat(approved.get()).isEqualTo(startedRun);

            // Decision queue: park one, list it, resolve it.
            UUID decisionId = UUID.randomUUID();
            store.append(() -> {
                store.root().decisions.put(decisionId, new Decision(decisionId, null,
                    DecisionKind.BUDGET_EXTENSION, "budget gone", DecisionState.PENDING,
                    null, Instant.now()));
                return null;
            }).get();
            List<Decision> decisions = control.decisions();
            assertThat(decisions).hasSize(1);
            assertThat(decisions.get(0).getKind().name()).isEqualTo("BUDGET_EXTENSION");
            control.resolveDecision(decisionId.toString(), "granted +1M");
            assertThat(control.decisions().get(0).getState().name()).isEqualTo("RESOLVED");

            // Session events from a persisted trace.
            UUID sessionId = UUID.randomUUID();
            AgentSessionRecord record = new AgentSessionRecord(sessionId, startedRun, null, null, 0,
                "worker-0", "qwen36-27b", 0.2, Instant.now(), Instant.now(), "COMPLETED", null,
                3, 500, List.of(
                    new TraceEvent(0, Instant.now(), TraceEventKind.SESSION_OPENED, "worker-0", "sys", null, 0),
                    new TraceEvent(1, Instant.now(), TraceEventKind.TOOL_CALL, "apply_diff", "{...}", null, 100)));
            store.append(() -> {
                store.root().agentSessions().put(sessionId, Lazy.Reference(record));
                return null;
            }).get();

            List<TraceEventDto> events = observer.sessionEvents(sessionId.toString(), 0, 100);
            assertThat(events).hasSize(2);
            assertThat(events.get(1).getKind()).isEqualTo("TOOL_CALL");
            assertThat(events.get(1).getLabel()).isEqualTo("apply_diff");

            List<TraceEventDto> fromSeq1 = observer.sessionEvents(sessionId.toString(), 1, 100);
            assertThat(fromSeq1).hasSize(1);

            // Post-analysis browsing + blob fetch (O2).
            assertThat(observer.recentSessions(10)).hasSize(1);
            assertThat(observer.recentSessions(10).get(0).getSessionId()).isEqualTo(sessionId.toString());
            assertThat(observer.blobText("missing-ref"))
                .as("no blob store configured in this context").isEmpty();

            // Insights aggregation (O3): the session's tokens roll up.
            InsightsDto insights = observer.insights();
            assertThat(insights.getTotalRuns()).isZero(); // no runs stored in this test
            assertThat(insights.getTotalSessions()).isEqualTo(1);
            assertThat(insights.getTotalTokens()).isEqualTo(500);
        }
    }

    /**
     * The bulk resolve, which exists because the queue arrives in bursts: one model-server outage
     * mints a BLOCKED_TASK per abandoned task, and clearing twenty-one of them one at a time is
     * work nobody does — so the badge stays wrong for ever.
     *
     * <p>Three guarantees, and all three have a way of going wrong. Every unanswered decision ends
     * up RESOLVED carrying the SHARED response, so the record says why. An already-resolved one is
     * not rewritten — a sweep that overwrote the individual answers already given would destroy the
     * very judgements this queue exists to record. And an APPROVAL is left alone, because it is
     * answered by approving or rejecting the RUN: sweeping it to RESOLVED would take the gate off
     * the queue while the run stayed parked at APPROVAL with nothing left to say so.
     */
    @Test
    void resolvingEveryPendingDecisionWritesOneSharedAnswerAndRewritesNothingElse() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { }));
            ControlServiceImpl control = new ControlServiceImpl();

            // The outage: three blocked tasks, all saying the same thing.
            List<UUID> blocked = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                blocked.add(UUID.randomUUID());
            }
            UUID budget = UUID.randomUUID();
            UUID alreadyAnswered = UUID.randomUUID();
            UUID approval = UUID.randomUUID();
            store.append(() -> {
                for (UUID id : blocked) {
                    store.root().decisions.put(id, new Decision(id, UUID.randomUUID(),
                        DecisionKind.BLOCKED_TASK, "no candidate survived", DecisionState.PENDING,
                        null, Instant.now()));
                }
                store.root().decisions.put(budget, new Decision(budget, null,
                    DecisionKind.BUDGET_EXTENSION, "budget gone", DecisionState.PENDING,
                    null, Instant.now()));
                store.root().decisions.put(alreadyAnswered, new Decision(alreadyAnswered, null,
                    DecisionKind.BLOCKED_TASK, "an older one", DecisionState.RESOLVED,
                    "looked at it by hand", Instant.now()));
                store.root().decisions.put(approval, new Decision(approval, UUID.randomUUID(),
                    DecisionKind.APPROVAL, "approve?", DecisionState.PENDING,
                    null, Instant.now()));
                return null;
            }).get();

            assertThat(control.resolveAllPendingDecisions("Spark was down, retried the stories"))
                .as("the four unanswered non-approval decisions, and only those")
                .isEqualTo(4);

            // Durable before the read-back: resolveAllPendingDecisions awaits the store append for
            // exactly this reason, so the very next list is allowed to be believed.
            var byId = control.decisions().stream()
                .collect(java.util.stream.Collectors.toMap(Decision::id, d -> d));
            for (UUID id : blocked) {
                assertThat(byId.get(id).state()).isEqualTo(DecisionState.RESOLVED);
                assertThat(byId.get(id).humanResponse())
                    .isEqualTo("Spark was down, retried the stories");
            }
            assertThat(byId.get(budget).state()).isEqualTo(DecisionState.RESOLVED);
            assertThat(byId.get(budget).humanResponse())
                .isEqualTo("Spark was down, retried the stories");

            // The one that was already answered keeps the answer it was given.
            assertThat(byId.get(alreadyAnswered).humanResponse()).isEqualTo("looked at it by hand");

            // The approval is untouched, and still owed.
            assertThat(byId.get(approval).state()).isEqualTo(DecisionState.PENDING);
            assertThat(byId.get(approval).humanResponse()).isNull();

            // Nothing left to sweep, and saying so costs nothing — a second press is a no-op, not
            // a rewrite of the answers just recorded.
            assertThat(control.resolveAllPendingDecisions("again")).isZero();
            assertThat(control.decisions().stream()
                .filter(d -> d.state() == DecisionState.RESOLVED)
                .map(Decision::humanResponse))
                .containsExactlyInAnyOrder("looked at it by hand",
                    "Spark was down, retried the stories", "Spark was down, retried the stories",
                    "Spark was down, retried the stories", "Spark was down, retried the stories");
        }
    }

    @Test
    void settingsAndGuidelinesRoundTrip() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            AtomicReference<String> savedYaml = new AtomicReference<>("initial: yaml");
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withSettings(savedYaml::get, yaml -> { savedYaml.set(yaml); return ""; }));

            ControlServiceImpl control = new ControlServiceImpl();

            // Settings YAML round-trip through the context callbacks.
            assertThat(control.settingsYaml()).isEqualTo("initial: yaml");
            assertThat(control.saveSettingsYaml("consolePort: 9090")).isEmpty();
            assertThat(control.settingsYaml()).isEqualTo("consolePort: 9090");

            // Guidelines listing.
            UUID gid = UUID.randomUUID();
            store.append(() -> {
                store.root().guidelines.put(gid, new LearnedGuideline(
                    gid, 1, GuidelineScope.PROJECT, "records",
                    "Prefer records.", new Provenance("human", null),
                    1.0, Instant.now(), 0, GuidelineStatus.ACTIVE));
                return null;
            }).get();
            List<GuidelineDto> guidelines = control.guidelines();
            assertThat(guidelines).hasSize(1);
            assertThat(guidelines.get(0).getSlug()).isEqualTo("records");
            assertThat(guidelines.get(0).getStatus()).isEqualTo("ACTIVE");
            assertThat(guidelines.get(0).getSource()).isEqualTo("human");
        }
    }

    @Test
    void projectSwitcherListsCreatesAndSwitches() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            List<Project> projects = new ArrayList<>();
            Project alpha = new Project(UUID.randomUUID(),
                "alpha", "C:/work/alpha", List.of("C:/work/zeroz4j"), Instant.now(), false);
            projects.add(alpha);
            AtomicReference<UUID> current = new AtomicReference<>(alpha.id());

            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(
                    () -> projects,
                    current::get,
                    (name, path, ctx) -> {
                        Project np = new Project(
                            UUID.randomUUID(), name, path, ctx, Instant.now(), false);
                        projects.add(np);
                        return np;
                    },
                    current::set));

            ControlServiceImpl control = new ControlServiceImpl();

            // List: alpha is current, its context folder is CSV-encoded.
            List<ProjectDto> listed = control.projects();
            assertThat(listed).hasSize(1);
            assertThat(listed.get(0).getName()).isEqualTo("alpha");
            assertThat(listed.get(0).isCurrent()).isTrue();
            assertThat(listed.get(0).getContextPathsCsv()).isEqualTo("C:/work/zeroz4j");

            // Create (CSV context list is parsed) and switch.
            String newId = control.createProject("beta", "C:/work/beta", "C:/work/zeroz4j, C:/work/lib");
            assertThat(newId).doesNotStartWith("error");
            assertThat(control.projects()).hasSize(2);
            control.switchProject(newId);
            assertThat(current.get().toString()).isEqualTo(newId);
            assertThat(control.projects().stream()
                .filter(ProjectDto::isCurrent)
                .map(ProjectDto::getName)).containsExactly("beta");
        }
    }

    @Test
    void deletingAProjectRequiresTheTypedNameAndMovesOffTheCurrentProject() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            Project doomed = store.ensureProject("doomed", "C:/work/doomed", List.of());
            Project keeper = store.ensureProject("keeper", "C:/work/keeper", List.of());
            store.saveChat(new com.swarmcoder.domain.ChatSession(UUID.randomUUID(), doomed.id(),
                "notes", Instant.now(), null, false));
            AtomicReference<UUID> current = new AtomicReference<>(doomed.id());

            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(store::listProjects, current::get,
                    (n, p, c) -> null, current::set));
            ControlServiceImpl control = new ControlServiceImpl();

            // The confirm step's sentence is built server-side, from real counts.
            String preview = control.projectDeletionPreview(doomed.id().toString());
            assertThat(preview).startsWith("This permanently deletes").contains("chats (1)");

            // THE GUARD: the typed name is re-checked here, so a client that skips its own
            // confirm step (or sends anything at all) is refused and nothing is removed.
            assertThat(control.deleteProject(doomed.id().toString(), "keeper"))
                .startsWith("error:");
            assertThat(control.deleteProject(doomed.id().toString(), ""))
                .startsWith("error:");
            assertThat(store.getProject(doomed.id())).isNotNull();

            assertThat(control.deleteProject(doomed.id().toString(), "doomed")).isEmpty();
            assertThat(store.getProject(doomed.id())).isNull();
            assertThat(store.listChats(doomed.id())).isEmpty();
            // The machinery moved off the deleted record — everything resolves through the current
            // project, so it may never point at one that is gone.
            assertThat(current.get()).isEqualTo(keeper.id());
            assertThat(store.getProject(keeper.id())).isNotNull();

            // …and moving is NOT opening. This is the whole of the operator's report: a deletion
            // used to fill the screen back up with the next project in the list, which looks
            // exactly like the deletion having failed — and the obvious response to that is to
            // delete again, destroying a project nobody meant to touch. So the registry the
            // browser is sent says the operator has chosen nothing, with no project suggested,
            // which is what puts the picker up instead of another project's workspace.
            ProjectList afterDelete = ProjectPublisher.build();
            assertThat(afterDelete.isChosen())
                .describedAs("a deletion leaves NO project chosen, so the console asks instead of "
                    + "opening the next one by itself")
                .isFalse();
            assertThat(afterDelete.getSuggestedProjectId())
                .describedAs("and it suggests nothing: what happens to be left standing after a "
                    + "deletion is not a remembered choice")
                .isEmpty();
            assertThat(afterDelete.isPublished()).isTrue();
            assertThat(afterDelete.getProjects()).extracting(ProjectDto::getName)
                .describedAs("the deleted project is gone from the list the picker draws")
                .containsExactly("keeper");

            // Choosing one answers the question — and only choosing does.
            control.switchProject(keeper.id().toString());
            assertThat(ProjectPublisher.build().isChosen()).isTrue();

            assertThat(control.deleteProject("not-a-uuid", "doomed")).startsWith("error:");
            assertThat(control.projectDeletionPreview(UUID.randomUUID().toString()))
                .startsWith("error:");
        }
    }

    @Test
    void chatFreeformTurnAndRunCommandRoundTrip() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID projectId = UUID.randomUUID();
            UUID startedRun = UUID.randomUUID();
            AtomicReference<String> intakeGoal = new AtomicReference<>();
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> {
                    intakeGoal.set(kind + "|" + goal);
                    // Park the run at APPROVAL immediately so narration has a state to report.
                    try {
                        store.append(() -> {
                            store.root().runs.put(startedRun, new Run(
                                startedRun, WorkflowKind.GREENFIELD,
                                RunState.APPROVAL, null, null, null, null, null,
                                Instant.now(), null));
                            return null;
                        }).get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return startedRun;
                }, r -> { }, r -> { })
                .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
                // Scripted coder: streams three chunks.
                .withChat((messages, model) -> Stream.of("Hello ", "**world**", "!")));

            ChatServiceImpl chats = new ChatServiceImpl();
            String chatId = chats.createChat(null);
            assertThat(chats.chats()).hasSize(1);
            assertThat(chats.chats().get(0).getTitle()).isEqualTo("New chat");

            // Freeform turn: USER persists immediately, CODER lands when the stream completes.
            assertThat(chats.send(chatId, "hi there")).isEmpty();
            awaitMessages(chats, chatId, 2);
            var history = chats.history(chatId);
            assertThat(history.get(0).getRole()).isEqualTo("USER");
            assertThat(history.get(1).getRole()).isEqualTo("CODER");
            assertThat(history.get(1).getMarkdown()).isEqualTo("Hello **world**!");
            assertThat(chats.chats().get(0).getTitle()).isEqualTo("hi there"); // auto-titled

            // Slash command: starts a run, binds it, narrates the APPROVAL park. The turn is
            // processed on the per-chat pump thread, so wait for the intake to fire.
            assertThat(chats.send(chatId, "/run add multiply to Calculator")).isEmpty();
            awaitMessages(chats, chatId, 5); // +USER +RUN_EVENT(started) +RUN_EVENT(APPROVAL)
            assertThat(intakeGoal.get()).isEqualTo("GREENFIELD|add multiply to Calculator");
            var afterRun = chats.history(chatId);
            assertThat(afterRun.get(3).getKind()).isEqualTo("RUN_EVENT");
            assertThat(afterRun.get(3).getRunId()).isEqualTo(startedRun);
            assertThat(afterRun.get(4).getMarkdown()).contains("APPROVAL");
            assertThat(store.root().chats().get(UUID.fromString(chatId)).boundRunId())
                .isEqualTo(startedRun);
        }
    }

    @Test
    void knowledgeDocsCrudRoundTripThroughTheStore() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID projectId = UUID.randomUUID();
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { }));
            ControlServiceImpl control = new ControlServiceImpl();

            // Create (human, ACTIVE by default) — the domain object goes on the wire directly.
            var doc = new KnowledgeDoc();
            doc.setTitle("DI rule");
            doc.setBody("Constructor injection only.");
            String id = control.saveKnowledgeDoc(doc);
            assertThat(id).doesNotStartWith("error");
            var listed = control.knowledgeDocs();
            assertThat(listed).hasSize(1);
            assertThat(listed.get(0).getStatus()).isEqualTo("ACTIVE");
            assertThat(listed.get(0).getSource()).isEqualTo("human");
            assertThat(listed.get(0).getSlug()).isEqualTo("di-rule");
            assertThat(store.listKnowledgeDocs(projectId)).hasSize(1);

            // A mined proposal accepts into ACTIVE.
            var proposal = new KnowledgeDoc(UUID.randomUUID(), projectId,
                "pattern", "Pattern", "body", "PROPOSED", "extraction", Instant.now(), Instant.now());
            store.saveKnowledgeDoc(proposal);
            assertThat(control.acceptKnowledgeDoc(proposal.id().toString())).isEmpty();
            assertThat(store.getKnowledgeDoc(proposal.id()).status()).isEqualTo("ACTIVE");

            // Update + delete.
            doc.setId(UUID.fromString(id));
            doc.setBody("Constructor injection only. No service locators.");
            assertThat(control.saveKnowledgeDoc(doc)).isEqualTo(id);
            assertThat(store.getKnowledgeDoc(UUID.fromString(id)).body()).contains("service locators");
            assertThat(control.deleteKnowledgeDoc(id)).isEmpty();
            assertThat(store.listKnowledgeDocs(projectId)).hasSize(1);
        }
    }

    @Test
    void brdRequirementGraphCrudRoundTripsThroughTheStore() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID projectId = UUID.randomUUID();
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { }));
            BrdServiceImpl brd = new BrdServiceImpl();

            // First read creates an empty, project-scoped BRD (domain Brd on the wire, no DTO).
            assertThat(brd.brd().requirements()).isEmpty();
            assertThat(brd.brd().projectId()).isEqualTo(projectId);

            // Create two requirement nodes (server assigns ids + R-handles). DRAFT, because
            // nothing becomes agreed scope without a check that names a test which could prove it
            // (DEVELOPER_CORRECTIONS.md §20.2) — a save that tried would be refused, and
            // AgreementGateTest is where that is pinned.
            assertThat(brd.saveRequirement(new BrdRequirement(null, null, "Login", "Users can log in",
                Priority.HIGH, RequirementStatus.DRAFT, null))).isEmpty();
            assertThat(brd.saveRequirement(new BrdRequirement(null, null, "OAuth", "Google sign-in",
                Priority.MEDIUM, null, null))).isEmpty();

            Brd graph = brd.brd();
            assertThat(graph.requirements()).hasSize(2);
            assertThat(graph.requirements().get(0).handle()).isEqualTo("R1");
            assertThat(graph.requirements().get(1).handle()).isEqualTo("R2");
            UUID id1 = graph.requirements().get(0).id();
            UUID id2 = graph.requirements().get(1).id();

            // Add a typed edge R2 --refines--> R1.
            assertThat(brd.saveEdge(new BrdEdge(id2, id1, RequirementRelation.REFINES))).isEmpty();
            assertThat(brd.brd().edges()).hasSize(1);
            assertThat(brd.brd().edges().get(0).relation()).isEqualTo(RequirementRelation.REFINES);

            // Self-edge is rejected.
            assertThat(brd.saveEdge(new BrdEdge(id1, id1, RequirementRelation.DEPENDS_ON)))
                .startsWith("error");

            // Update a requirement (edit its text).
            BrdRequirement r1 = graph.requirements().get(0);
            assertThat(brd.saveRequirement(new BrdRequirement(r1.id(), r1.handle(),
                r1.title(), "Users can log in with email + password", r1.priority(), r1.status(),
                r1.category()))).isEmpty();

            // --- an edit that sends no category must not erase the one on record ------------------
            // The editor stopped offering a Category field (REQUIREMENTS_AT_SCALE_DESIGN §3.5), so
            // every save from it now arrives with that field null. Extraction fills it from the
            // document heading a requirement came from, which is real provenance — and setting it
            // unconditionally would have wiped all of it on the first edit of each requirement,
            // silently and irreversibly.
            BrdRequirement categorised = graph.requirements().get(0);
            categorised.setCategory("Authentication");
            assertThat(brd.saveRequirement(categorised)).isEmpty();
            assertThat(brd.brd().requirements().get(0).category()).isEqualTo("Authentication");
            assertThat(brd.saveRequirement(new BrdRequirement(categorised.id(), categorised.handle(),
                categorised.title(), "reworded again", categorised.priority(), categorised.status(),
                null))).isEmpty();
            assertThat(brd.brd().requirements().get(0).category())
                .describedAs("a form that no longer edits the category must not destroy it")
                .isEqualTo("Authentication");
            assertThat(brd.brd().requirements().get(0).text())
                .describedAs("…while the field it DID edit still changes")
                .isEqualTo("reworded again");

            // "Delete" retires: nothing in the requirements is destroyed (author decision
            // 2026-08-28). R1 stays, its link to R2 stays, and the trail from R1 to whatever was
            // built for it stays — what changes is that it is out of scope and out of every count.
            assertThat(brd.retireRequirement(id1.toString())).isEmpty();
            Brd after = brd.brd();
            assertThat(after.requirements()).hasSize(2);
            assertThat(after.edges()).hasSize(1);
            BrdRequirement retired = after.requirements().stream()
                .filter(r -> r.id().equals(id1)).findFirst().orElseThrow();
            assertThat(retired.status()).isEqualTo(RequirementStatus.DEPRECATED);
            assertThat(retired.isRetired()).isTrue();
            assertThat(retired.gatingCriteria())
                .describedAs("out of scope means it gates nothing, whatever checks it still carries")
                .isEmpty();
            // On the list, R1 is still drawn - but only because R2 is a part of it and a match may
            // never be orphaned from its place in the hierarchy. It is dimmed and marked as context,
            // not presented as something in scope.
            com.swarmcoder.console.api.RequirementPageDto page =
                brd.rows(new com.swarmcoder.console.api.RequirementQuery(), 0, 50);
            assertThat(page.getRows()).hasSize(2);
            assertThat(page.getMatches()).isEqualTo(1);
            assertThat(page.getContextParents()).isEqualTo(1);
            assertThat(page.getRows().get(0).getHandle()).isEqualTo("R1");
            assertThat(page.getRows().get(0).isContextOnly()).isTrue();

            // Retire the part as well and there is nothing left in scope: both leave the list, and
            // the footer counts them rather than letting them vanish.
            assertThat(brd.retireRequirement(id2.toString())).isEmpty();
            com.swarmcoder.console.api.RequirementPageDto emptied =
                brd.rows(new com.swarmcoder.console.api.RequirementQuery(), 0, 50);
            assertThat(emptied.getRows()).isEmpty();
            assertThat(emptied.getRetiredHidden()).isEqualTo(2);
            assertThat(emptied.getTotal())
                .describedAs("they are still in the project - the list is what they left")
                .isEqualTo(2);

            // One click asks for them and both are there again, with their link intact.
            com.swarmcoder.console.api.RequirementQuery withRetired =
                new com.swarmcoder.console.api.RequirementQuery();
            withRetired.setIncludeRetired(1);
            assertThat(brd.rows(withRetired, 0, 50).getRows()).hasSize(2);
            assertThat(brd.retireRequirement(id1.toString()))
                .describedAs("and retiring it twice is refused rather than silently repeated")
                .startsWith("error");
            // (BrdStoreTest covers store-reopen persistence of the graph.)
        }
    }

    @Test
    void researcherStartAndStatusRoundTripThroughTheBridge() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            AtomicReference<String> startedTopic = new AtomicReference<>();
            AtomicReference<String> statusValue = new AtomicReference<>("idle");
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(List::of, () -> UUID.randomUUID(), (n, p, c) -> null, id -> { })
                .withResearcher(new ConsoleContext.Researcher() {
                    @Override
                    public String start(String topic) {
                        startedTopic.set(topic);
                        statusValue.set("researching: " + topic + "…");
                        return "";
                    }
                    @Override
                    public String status() {
                        return statusValue.get();
                    }
                }));

            ControlServiceImpl control = new ControlServiceImpl();
            assertThat(control.researchStatus()).isEqualTo("idle");
            assertThat(control.startResearch("Vaadin grids")).isEmpty();
            assertThat(startedTopic.get()).isEqualTo("Vaadin grids");
            assertThat(control.researchStatus()).contains("researching");
        }
    }

    @Test
    void analystResearchLoopExecutesToolsBeforeAnswering() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            AtomicInteger call = new AtomicInteger();
            AtomicReference<String> toolResultSeen = new AtomicReference<>();
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(List::of, () -> UUID.randomUUID(), (n, p, c) -> null, id -> { })
                // Turn 1: the model asks for a tool. Turn 2: it answers using the result.
                .withChat((messages, model) -> {
                    if (call.incrementAndGet() == 1) {
                        return Stream.of("TOOL read_file zeroz4j/ui/Button.java");
                    }
                    toolResultSeen.set(messages.get(messages.size() - 1).get("content"));
                    return Stream.of("Buttons use `addClickListener` — confirmed from source.");
                })
                .withChatTools(new ConsoleContext.ChatTools() {
                    @Override
                    public String listFolder(String address) {
                        return "";
                    }
                    @Override
                    public String readFile(String address) {
                        return "public class Button { public void addClickListener(Runnable r) {} }";
                    }
                    @Override
                    public String searchCode(String query) {
                        return "";
                    }
                    @Override
                    public String lookupDocs(String query) {
                        return "";
                    }
                    @Override
                    public List<String> mentionCandidates(String query) {
                        return List.of();
                    }
                }));

            ChatServiceImpl chats = new ChatServiceImpl();
            String chatId = chats.createChat("research");
            chats.send(chatId, "how do zeroz4j buttons handle clicks?");
            awaitMessages(chats, chatId, 3); // USER + TOOL chip + CODER answer

            var history = chats.history(chatId);
            assertThat(history.get(1).getKind()).isEqualTo("TOOL");
            assertThat(history.get(1).getMarkdown()).contains("read_file zeroz4j/ui/Button.java");
            assertThat(history.get(2).getRole()).isEqualTo("CODER");
            assertThat(history.get(2).getMarkdown()).contains("addClickListener");
            assertThat(toolResultSeen.get()).contains("public class Button");
        }
    }

    @Test
    void atMentionInjectsReferencedFileIntoThePrompt() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            AtomicReference<String> userContentSeen = new AtomicReference<>();
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(List::of, () -> UUID.randomUUID(), (n, p, c) -> null, id -> { })
                .withChat((messages, model) -> {
                    // The last message is the current user turn — capture what the model receives.
                    userContentSeen.set(messages.get(messages.size() - 1).get("content"));
                    return Stream.of("Looks fine.");
                })
                .withChatTools(new ConsoleContext.ChatTools() {
                    @Override
                    public String listFolder(String address) {
                        return "";
                    }
                    @Override
                    public String readFile(String address) {
                        return "SENTINEL_BODY for " + address;
                    }
                    @Override
                    public String searchCode(String query) {
                        return "";
                    }
                    @Override
                    public String lookupDocs(String query) {
                        return "";
                    }
                    @Override
                    public List<String> mentionCandidates(String query) {
                        return List.of("sc-domain/src/Task.java");
                    }
                }));

            ChatServiceImpl chats = new ChatServiceImpl();
            String chatId = chats.createChat("mention");
            chats.send(chatId, "review @sc-domain/src/Task.java for issues");
            awaitMessages(chats, chatId, 2); // USER + CODER

            assertThat(userContentSeen.get()).contains("SENTINEL_BODY for sc-domain/src/Task.java");
            assertThat(userContentSeen.get()).contains("Referenced files");
        }
    }

    @Test
    void forkCopiesTranscriptAndModelPickerListsAndSetsModels() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(List::of, () -> null, (n, p, c) -> null, id -> { })
                .withChatModels(() -> List.of("qwen36-27b", "deepseek-v4-flash")));

            ChatServiceImpl chats = new ChatServiceImpl();
            String chatId = chats.createChat("original");
            UUID cid = UUID.fromString(chatId);
            store.appendChatMessage(new ChatMessage(UUID.randomUUID(), cid, 0,
                "USER", "TEXT", "build a thing", null, null, Instant.now(), 0));
            store.appendChatMessage(new ChatMessage(UUID.randomUUID(), cid, 0,
                "CODER", "TEXT", "sure", null, null, Instant.now(), 0));

            // Per-chat model override: the picker lists models and setting one persists.
            assertThat(chats.availableModels()).containsExactly("qwen36-27b", "deepseek-v4-flash");
            chats.setChatModel(chatId, "deepseek-v4-flash");
            assertThat(store.root().chats().get(cid).modelOverride()).isEqualTo("deepseek-v4-flash");

            // Fork copies the transcript into a new, distinct chat (override carried too).
            String forkId = chats.fork(chatId);
            assertThat(forkId).isNotEqualTo(chatId);
            assertThat(chats.history(forkId)).hasSize(2);
            assertThat(chats.history(forkId).get(0).getMarkdown()).isEqualTo("build a thing");
            assertThat(store.root().chats().get(UUID.fromString(forkId)).modelOverride())
                .isEqualTo("deepseek-v4-flash");
        }
    }

    @Test
    void bareRunPicksUpTheAnalystsProposedGoal() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            AtomicReference<String> intakeGoal = new AtomicReference<>();
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> {
                    intakeGoal.set(kind + "|" + goal);
                    return UUID.randomUUID();
                }, r -> { }, r -> { })
                .withProjects(List::of, () -> UUID.randomUUID(), (n, p, c) -> null, id -> { }));

            ChatServiceImpl chats = new ChatServiceImpl();
            String chatId = chats.createChat("scoping");

            // No proposal yet: bare /run must refuse with guidance, not start a run.
            chats.send(chatId, "/run");
            awaitMessages(chats, chatId, 2);
            assertThat(chats.history(chatId).get(1).getKind()).isEqualTo("ERROR");
            assertThat(intakeGoal.get()).isNull();

            // The analyst proposed a goal → bare /run starts the run with it.
            store.appendChatMessage(new ChatMessage(UUID.randomUUID(),
                UUID.fromString(chatId), 0, "CODER", "TEXT",
                "Requirements summary...\n\n**PROPOSED RUN GOAL:** Build a folder-browser GUI "
                + "with a folder selector.\n\nType /run to start.", null, null, Instant.now(), 0));
            chats.send(chatId, "/run");
            awaitMessages(chats, chatId, 5); // +USER +RUN_EVENT
            assertThat(intakeGoal.get()).isEqualTo(
                "GREENFIELD|Build a folder-browser GUI with a folder selector.");
        }
    }

    private static void awaitMessages(ChatServiceImpl chats, String chatId, int minCount)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (chats.history(chatId).size() >= minCount) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("chat never reached " + minCount + " messages; has "
            + chats.history(chatId).size());
    }

    @Test
    void graphSnapshotAssemblesStagesTasksAndCandidates() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { }));

            UUID runId = UUID.randomUUID();
            UUID taskA = UUID.randomUUID();
            UUID taskB = UUID.randomUUID();
            var graph = new TaskGraph(UUID.randomUUID(), 1, null,
                List.of(
                    new Task(taskA, 1, "add multiply", "", Set.of("src/main"),
                        Set.of(), List.of(), null, null, null,
                        new SwarmPolicy(2, false, 0.2, 0.4, List.of()),
                        TaskState.DONE),
                    new Task(taskB, 1, "add tests", "", Set.of("src/test"),
                        Set.of(), List.of(), null, null, null,
                        new SwarmPolicy(2, false, 0.2, 0.4, List.of()),
                        TaskState.PENDING)),
                List.of(new TaskEdge(taskA, taskB)));
            store.append(() -> {
                store.root().taskGraphs.put(graph.id(), graph);
                store.root().runs.put(runId, new Run(runId,
                    WorkflowKind.GREENFIELD,
                    RunState.EXECUTING, null, null, null, graph.id(), null,
                    Instant.now(), new RunReport(runId, "multiply feature")));
                store.root().candidateArchives.put(UUID.randomUUID(), Lazy.Reference(
                    new CandidateSolution(UUID.randomUUID(), taskA, 3,
                        "swarm/x/3", new SamplingConfig("qwen36-27b", 0.4, 0, "p", "s"),
                        "diff", null, new ClusterId("abc123", 2),
                        new JudgeScore(0.9, "solid", "judge"),
                        CandidateState.SELECTED, null)));
                return null;
            }).get();

            var snapshot = new GraphServiceImpl().snapshot(runId.toString());
            assertThat(snapshot.getRunState()).isEqualTo("EXECUTING");
            assertThat(snapshot.getGoal()).isEqualTo("multiply feature");
            assertThat(snapshot.getTasks()).hasSize(2);
            var taskBDto = snapshot.getTasks().stream()
                .filter(t -> t.getTaskId().equals(taskB.toString())).findFirst().orElseThrow();
            assertThat(taskBDto.getDependsOnCsv()).isEqualTo(taskA.toString());
            assertThat(snapshot.getCandidates()).hasSize(1);
            var candidate = snapshot.getCandidates().get(0);
            assertThat(candidate.getState()).isEqualTo("SELECTED");
            assertThat(candidate.getWorkerIndex()).isEqualTo(3);
            assertThat(candidate.getModel()).isEqualTo("qwen36-27b");
            assertThat(candidate.getJudgeScore()).isEqualTo(0.9);
        }
    }

    @Test
    void observerScopesRunsToTheCurrentProject() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            UUID projectA = UUID.randomUUID();
            UUID projectB = UUID.randomUUID();
            AtomicReference<UUID> current = new AtomicReference<>(projectA);
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(List::of, current::get, (n, p, c) -> null, current::set));

            UUID runA = UUID.randomUUID();
            UUID runB = UUID.randomUUID();
            store.append(() -> {
                var ra = new Run(runA, WorkflowKind.GREENFIELD,
                    RunState.APPROVAL, projectA, null, null, null, null, Instant.now(),
                    new RunReport(runA, "goal A"));
                var rb = new Run(runB, WorkflowKind.GREENFIELD,
                    RunState.APPROVAL, projectB, null, null, null, null, Instant.now(),
                    new RunReport(runB, "goal B"));
                store.root().runs.put(runA, ra);
                store.root().runs.put(runB, rb);
                return null;
            }).get();

            ObserverServiceImpl observer = new ObserverServiceImpl();
            // Current = A → only A's run is listed; switching to B flips it.
            assertThat(observer.listRuns())
                .extracting(RunSummaryDto::getGoal).containsExactly("goal A");
            current.set(projectB);
            assertThat(observer.listRuns())
                .extracting(RunSummaryDto::getGoal).containsExactly("goal B");
        }
    }

    // --- C2-6 chat polish -----------------------------------------------------------------

    /**
     * Retargeted 2026-07-27, when {@code /backlog} was removed: this used to drive the backlog
     * authoring mode, which shared the transcript builder with freeform chat and was the reason the
     * builder exists. With the modes gone there is one caller left, so the test asserts the same
     * guarantee — an @mention on the current turn reaches the model as the file's CONTENTS, not as
     * a bare path — through the surface that remains.
     */
    @Test
    void mentionsAreExpandedIntoTheFileTheyName() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            AtomicReference<String> userContentSeen = new AtomicReference<>();
            UUID projectId = UUID.randomUUID();
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(List::of, () -> projectId, (n, p, c) -> null, id -> { })
                .withChat((messages, model) -> {
                    userContentSeen.set(messages.get(messages.size() - 1).get("content"));
                    return Stream.of("Noted.");
                })
                .withChatTools(readFileTools("SENTINEL_SPEC")));

            ChatServiceImpl chats = new ChatServiceImpl();
            String chatId = chats.createChat("mentions");
            chats.send(chatId, "what does @docs/spec.md say about pricing?");
            awaitMessages(chats, chatId, 2);

            // A caller that copied the raw markdown made the autocomplete appear to work while the
            // model received a bare path. Every caller now shares one transcript builder.
            assertThat(userContentSeen.get()).contains("SENTINEL_SPEC for docs/spec.md");
        }
    }

    @Test
    void theAnalystRunsOutOfQuestionsAndMustPropose() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            AtomicReference<String> lastPrompt = new AtomicReference<>("");
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(List::of, () -> UUID.randomUUID(), (n, p, c) -> null, id -> { })
                .withChat((messages, model) -> {
                    lastPrompt.set(messages.get(messages.size() - 1).get("content"));
                    return Stream.of("And one more thing — which database?");
                }));

            ChatServiceImpl chats = new ChatServiceImpl();
            String chatId = chats.createChat("interview");

            // Three rounds of questions is the budget.
            chats.send(chatId, "build me a thing");
            awaitMessages(chats, chatId, 2);
            assertThat(lastPrompt.get()).doesNotContain("which is the limit");

            chats.send(chatId, "a web thing");
            awaitMessages(chats, chatId, 4);
            chats.send(chatId, "for customers");
            awaitMessages(chats, chatId, 6);

            // The fourth turn is DIRECTED to converge. The persona already asked for "at most ~8"
            // questions, which a model is free to ignore forever; this is the enforcement.
            chats.send(chatId, "yes");
            awaitMessages(chats, chatId, 8);
            assertThat(lastPrompt.get()).contains("which is the limit");
            assertThat(lastPrompt.get()).contains("PROPOSED RUN GOAL");
            assertThat(lastPrompt.get()).contains("assumption");
        }
    }

    @Test
    void regenerateReplacesTheLastReplyRatherThanAppendingOne() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            AtomicReference<String> reply = new AtomicReference<>("first answer");
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(List::of, () -> UUID.randomUUID(), (n, p, c) -> null, id -> { })
                .withChat((messages, model) -> Stream.of(reply.get())));

            ChatServiceImpl chats = new ChatServiceImpl();
            String chatId = chats.createChat("regen");
            chats.send(chatId, "explain this");
            awaitMessages(chats, chatId, 2);

            reply.set("second answer");
            assertThat(chats.regenerate(chatId)).isEmpty();
            awaitMessages(chats, chatId, 2);   // still USER + CODER, not USER + CODER + CODER

            List<ChatMessage> transcript = chats.history(chatId);
            assertThat(transcript).hasSize(2);
            // The discarded answer is GONE: the transcript is also the model's context, so keeping
            // both would condition the next turn on a reply the operator had just rejected.
            assertThat(transcript.get(1).markdown()).isEqualTo("second answer");
        }
    }

    /**
     * The stages are ordered by dependency, and the ordering IS the feature: an empty BRD cannot be
     * promoted, and unpromoted requirements are not scope worth planning against. A later stage
     * leaking past an earlier one would send the operator to a surface that cannot help them.
     */
    @Test
    void theNextStepAdvancesThroughTheWorkflowStages() throws Exception {
        try (ArtifactStore store = new ArtifactStore(storeDir)) {
            Project project = store.ensureProject("next-step", storeDir.toString(), List.of());
            ConsoleContext.set(new ConsoleContext(store, new TraceHub(null),
                (goal, kind) -> UUID.randomUUID(), r -> { }, r -> { })
                .withProjects(store::listProjects, project::id, (n, p, c) -> null, id -> { }));
            ConsoleContext context = ConsoleContext.get();

            // Stage 1: a project whose BRD holds nothing. Note this temp folder is not a git
            // repository, so runs are impossible — the workflow step is computed anyway, because
            // authoring the BRD never needed one.
            ConsoleReadiness empty = ReadinessPublisher.build(context);
            assertThat(empty.nextAction()).isEqualTo(NextAction.ANALYSE_DOCUMENTS);
            assertThat(empty.nextActionText()).contains("BRD is empty");
            assertThat(empty.hasNextAction()).isTrue();
            assertThat(empty.hasProject()).isTrue();
            assertThat(empty.canRun()).isFalse();

            // Stage 2: one draft among agreed requirements still outranks planning — the draft is
            // scope nobody has agreed to, and designing against it is the mistake being prevented.
            Brd brd = store.ensureBrd(project.id());
            brd.requirements().add(new BrdRequirement(UUID.randomUUID(), "R1", "Login",
                "Users can log in", Priority.HIGH, RequirementStatus.DRAFT, null));
            brd.requirements().add(new BrdRequirement(UUID.randomUUID(), "R2", "OAuth",
                "Google sign-in", Priority.MEDIUM, RequirementStatus.ACTIVE, null));
            store.saveBrd(brd);

            ConsoleReadiness drafting = ReadinessPublisher.build(context);
            assertThat(drafting.nextAction()).isEqualTo(NextAction.PROMOTE_DRAFTS);
            assertThat(drafting.nextActionText()).startsWith("1 requirement is still a draft");

            // Stage 3: everything agreed, and an empty backlog — agreed scope with no work in it.
            brd.requirements().get(0).setStatus(RequirementStatus.ACTIVE);
            store.saveBrd(brd);

            ConsoleReadiness planning = ReadinessPublisher.build(context);
            assertThat(planning.nextAction()).isEqualTo(NextAction.PLAN_STORIES);
            assertThat(planning.nextActionText()).contains("no stories yet");

            // The setup fields the shell already depends on are untouched by any of this.
            assertThat(planning.projectName()).isEqualTo("next-step");
            assertThat(planning.runBlocker()).isNotNull();
        }
    }

    /** No project means no scope, so there is nothing to suggest and the bar must not render. */
    @Test
    void withoutAProjectThereIsNoNextStepAtAll() {
        assertThat(ConsoleReadiness.empty().nextAction()).isEqualTo(NextAction.NONE);
        assertThat(ConsoleReadiness.empty().hasNextAction()).isFalse();
    }

    @Test
    void commandsAreServedFromTheOrchestratorThatDispatchesThem() {
        List<String> commands = new ChatServiceImpl().commands();

        assertThat(commands).isNotEmpty();
        // Served rather than hardcoded in the client, so the UI whose job is to reveal the
        // commands cannot be the last thing to learn one was added.
        assertThat(commands).anyMatch(c -> c.startsWith("/run	"));
        // /brd and /backlog were removed when their guided flows replaced them — an undocumented
        // second path to the same outcome is the confusion the wizards exist to remove, and
        // nothing tests a path nobody is told about. Neither may come back here.
        assertThat(commands).noneMatch(c -> c.startsWith("/brd"));
        assertThat(commands).noneMatch(c -> c.startsWith("/backlog"));
        assertThat(commands).noneMatch(c -> c.startsWith("/done"));
    }

    /** ChatTools whose readFile returns a recognisable sentinel and nothing else does anything. */
    private static ConsoleContext.ChatTools readFileTools(String sentinel) {
        return new ConsoleContext.ChatTools() {
            @Override
            public String listFolder(String address) {
                return "";
            }
            @Override
            public String readFile(String address) {
                return sentinel + " for " + address;
            }
            @Override
            public String searchCode(String query) {
                return "";
            }
            @Override
            public String lookupDocs(String query) {
                return "";
            }
            @Override
            public List<String> mentionCandidates(String query) {
                return List.of("docs/spec.md");
            }
        };
    }
}
