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
package com.swarmcoder.store;

import org.eclipse.store.storage.embedded.types.EmbeddedStorage;
import org.eclipse.store.storage.embedded.types.EmbeddedStorageManager;
import org.eclipse.store.afs.nio.types.NioFileSystem;
import java.util.concurrent.ExecutionException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.AutonomousDecision;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdEdge;
import com.swarmcoder.domain.BrdNodePosition;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.BrdRevision;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.ChangeEntityType;
import com.swarmcoder.domain.ChangeEvent;
import com.swarmcoder.domain.ChangeKind;
import com.swarmcoder.domain.ChatMessage;
import com.swarmcoder.domain.ChatSession;
import com.swarmcoder.domain.CriterionVerification;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.FlowDiscussionTurn;
import com.swarmcoder.domain.FlowDocument;
import com.swarmcoder.domain.FlowProposal;
import com.swarmcoder.domain.FlowQuestion;
import com.swarmcoder.domain.GuidedFlow;
import com.swarmcoder.domain.Iteration;
import com.swarmcoder.domain.KnowledgeBrief;
import com.swarmcoder.domain.KnowledgeDoc;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.PendingExec;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.SourceDocument;
import com.swarmcoder.domain.Story;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import org.eclipse.serializer.reference.Lazy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class ArtifactStore implements AutoCloseable {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(ArtifactStore.class);
    private final EmbeddedStorageManager storageManager;
    private final StoreRoot root;
    private final ExecutorService writerThread;

    /** How long {@link #close()} waits for queued writes before giving up on them. */
    private static final long CLOSE_DRAIN_SECONDS = 60;

    public ArtifactStore(Path storageDir) {
        // Start WITHOUT a root, then adopt the persisted root if one exists. Passing a fresh
        // StoreRoot here and keeping a reference to it discards all persisted state on
        // restart — the exact bug that made crash-resume silently impossible.
        this.storageManager = EmbeddedStorage.start(storageDir);
        Object existingRoot = storageManager.root();
        if (existingRoot instanceof StoreRoot persisted) {
            this.root = persisted;
        } else {
            this.root = new StoreRoot();
            storageManager.setRoot(this.root);
            storageManager.storeRoot();
        }
        this.writerThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "swarmcoder-artifact-store-writer");
            t.setDaemon(true);
            return t;
        });
    }

    public StoreRoot root() {
        return root;
    }

    /**
     * Called after every durable write, on the writer thread. It exists so something can WAIT for
     * the store to change without polling it; a listener must do no more than wake a waiter.
     */
    private final List<Runnable> writeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Adds a listener told after every durable write. Adding the same one twice is a no-op. */
    public void addWriteListener(Runnable listener) {
        if (listener != null && !writeListeners.contains(listener)) {
            writeListeners.add(listener);
        }
    }

    private void written() {
        for (Runnable listener : writeListeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // a listener is a doorbell; a broken one must never fail a write
            }
        }
    }

    public <T> Future<T> append(Callable<T> mutation) {
        return writerThread.submit(() -> {
            T result = mutation.call();
            // storeRoot() alone is NOT enough: EclipseStore's default lazy storing skips
            // already-known instances, so mutations inside the root's maps would never reach
            // disk. Store the containers explicitly; new entries are persisted with them.
            storageManager.storeAll(root.projects, root.runs, root.designs, root.taskGraphs, root.briefs,
                root.guidelines, root.decisions, root.candidateArchives, root.agentSessions(),
                root.chats(), root.chatMessages(), root.knowledgeDocs(), root.brds(), root.brdRevisions(),
                root.stories(), root.iterations(), root.tasks(), root.sourceDocuments(),
                root.changeEvents(), root.criterionVerifications(),
                root.guidedFlows(), root.flowQuestions(), root.flowProposals(),
                root.flowDiscussions(), root.pendingExecs(), root.cloudSpend(), root.cloudBreaches());
            storageManager.storeRoot(); // covers newly initialized fields after class evolution
            written();
            return result;
        });
    }

    /**
     * Writes the cloud gate's counts and, when given, the limit that parked a run (or, with
     * {@code clearBreachOf}, forgets it). Queued, not awaited: the gate calls this on every charge.
     * Each record is a fresh copy, so the store sees a new instance and writes it.
     */
    public void recordCloudSpend(List<com.swarmcoder.domain.CloudSpendRecord> spend,
                                 com.swarmcoder.domain.CloudBreachRecord breach, UUID clearBreachOf) {
        writerThread.submit(() -> {
            for (var record : spend) {
                root.cloudSpend().put(record.scopeId(), record);
            }
            if (breach != null) {
                root.cloudBreaches().put(breach.runId(), breach);
            }
            if (clearBreachOf != null) {
                root.cloudBreaches().remove(clearBreachOf);
            }
            storageManager.storeAll(root.cloudSpend(), root.cloudBreaches());
            return null;
        });
    }

    /**
     * Records that a worker's {@code exec} tool call is about to run — see
     * {@link PendingExec} for why this is written before the process starts. Overwrites any
     * earlier pending exec for the same session (one command in flight at a time per session).
     *
     * <p>Blocks until the record is committed to disk, unlike {@link #storeChanged}'s other
     * callers: this one exists specifically so a crash immediately after this call still shows
     * the command that was about to run. Never let a caller skip the wait.
     */
    public void recordPendingExec(PendingExec exec) {
        try {
            writerThread.submit(() -> {
                root.pendingExecs().put(exec.sessionId(), exec);
                var storer = storageManager.createEagerStorer();
                storer.store(root.pendingExecs());
                storer.commit();
                return null;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted recording pending exec for session "
                + exec.sessionId(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to record pending exec for session "
                + exec.sessionId(), e);
        }
    }

    /**
     * Clears the pending-exec evidence for a session once its command has returned a result (or
     * the session closed). Fire-and-forget: unlike {@link #recordPendingExec}, losing this race
     * against a crash costs nothing — a stale entry is read as "this was the last exec running",
     * which for a session that is in fact dead is exactly the right answer.
     */
    public void clearPendingExec(UUID sessionId) {
        if (sessionId == null) {
            return;
        }
        writerThread.submit(() -> {
            if (root.pendingExecs().remove(sessionId) != null) {
                var storer = storageManager.createEagerStorer();
                storer.store(root.pendingExecs());
                storer.commit();
            }
            return null;
        });
    }

    /**
     * Force-persists {@code target} itself — the tool for a caller that mutated an ALREADY-KNOWN
     * object in place (a {@code Run} being parked or heartbeat-stamped, a {@code Task} recording its
     * state) rather than replacing it with a new one.
     *
     * <p>Storing the CONTAINER is not enough for that caller. {@code storeAll(root.runs, ...)}
     * registers new map entries, but EclipseStore's lazy default storer stops at an instance it
     * already knows, so a field set on a {@code Run} that is already in {@code root.runs} sits
     * correctly in memory for the rest of the process and is simply absent when the store is
     * reopened. The eager storer plus an explicit commit is the same durability guarantee
     * {@link #storeDeep} gives a BRD; a single {@code Run} or {@code Task} is smaller still, so
     * paying for it here is never the wrong trade.
     *
     * <p>Returns the {@link Future} rather than blocking, so a caller on a latency-sensitive path
     * (the heartbeat, which is stamped from the trace hub on a worker's own thread) can choose not
     * to wait. Nothing is lost by not waiting: {@link #close()} drains this queue before the
     * storage manager is closed.
     */
    public Future<?> storeChanged(Object target) {
        return writerThread.submit(() -> {
            var storer = storageManager.createEagerStorer();
            storer.store(target);
            storer.commit();
            written();
            return null;
        });
    }

    /**
     * The ONE way to record "this run's own fields changed" — parked, unparked, paused, stamped,
     * bound to a story, or given the commit its finished waves are on. Blocks until it is on disk.
     *
     * <p>Every one of those is a mutation of an existing {@code Run} instance rather than a
     * {@code Run.withState} replacement, and each used to be persisted its own way: some through a
     * container store that could not see the change at all, some through a direct storer call, some
     * not at all. One helper means a new mutation site cannot pick the wrong one.
     *
     * @return the same run, for chaining
     */
    public Run updateRun(Run run) {
        try {
            storeChanged(run).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted persisting run " + run.id(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist run " + run.id(), e);
        }
        return run;
    }

    /**
     * {@link #updateRun} without the wait — for the heartbeat alone, which is stamped on a worker's
     * own thread by the trace hub and must not make a worker step wait for a disk write. Durable
     * all the same: {@link #close()} drains the queue.
     */
    public Future<?> updateRunLater(Run run) {
        return storeChanged(run);
    }

    /**
     * Persists {@code target} AND everything it references, including objects the store already
     * knows about.
     *
     * <p>{@code store(x)} persists x's own fields but stops at already-known referenced instances —
     * EclipseStore's default lazy storing. That was survivable while a {@code Brd} was only edited by
     * REPLACING its requirement and edge lists, but a requirement now owns its
     * {@link AcceptanceCriterion}s, so editing one in place is an ordinary operation and the "always
     * replace the list" contract no longer holds. Rather than push that trap onto every caller, these
     * aggregates are stored eagerly. Safe because each is small and bounded: a BRD reaches only its
     * requirements, criteria and edges; a story reaches only ids and strings.
     */
    private void storeDeep(Object target) {
        var storer = storageManager.createEagerStorer();
        storer.store(target);
        storer.commit();
    }

    // --- Projects (multi-project) -------------------------------------------------------------

    /** Persists (creates or updates) a project; blocks until durable. */
    public Project saveProject(Project project) {
        try {
            return append(() -> {
                root.projects.put(project.id(), project);
                return project;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist project " + project.id(), e);
        }
    }

    /** All non-archived projects, newest first. */
    public List<Project> listProjects() {
        return root.projects.values().stream()
            .filter(p -> !p.archived())
            .sorted(Comparator.comparing(
                Project::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    /**
     * Records the project the operator switched to, so the next start reopens it.
     *
     * <p>Persisted rather than kept in config: which project you were in is session state, not
     * configuration, and writing it to {@code config.yaml} would put a moving value into a file the
     * operator hand-edits and version-controls.
     */
    public void setLastProject(UUID projectId) {
        try {
            append(() -> {
                root.setLastProjectId(projectId);
                return null;
            }).get();
        } catch (Exception e) {
            // Never fail a project switch because the bookmark could not be written.
            log.warn("Could not record the last project {}: {}", projectId, e.toString());
        }
    }

    /** The project last switched to, or null on a first run. */
    public UUID lastProjectId() {
        return root.lastProjectId();
    }

    public Project getProject(UUID id) {
        return id == null ? null : root.projects.get(id);
    }

    /**
     * Returns the existing project for {@code primaryPath}, or creates one. Used to migrate the
     * historical single-project setup into an explicit default project without losing data.
     */
    public Project ensureProject(String name, String primaryPath,
                                                       List<String> contextPaths) {
        String normalized = primaryPath == null ? null
            : Paths.get(primaryPath).toAbsolutePath().normalize().toString();
        for (Project p : root.projects.values()) {
            String existing = p.primaryPath() == null ? null
                : Paths.get(p.primaryPath()).toAbsolutePath().normalize().toString();
            if (!p.archived() && Objects.equals(existing, normalized)) {
                // Refresh the context folders from config. Returning the stored project untouched
                // meant editing contextPaths in config.yaml after first boot silently did nothing,
                // which reads as "the feature is broken" rather than "the value was ignored".
                List<String> incoming = contextPaths == null ? List.of() : List.copyOf(contextPaths);
                if (!incoming.isEmpty() && !incoming.equals(p.contextPaths())) {
                    p.setContextPaths(incoming);
                    saveProject(p);
                }
                return p;
            }
        }
        Project project = new Project(
            UUID.randomUUID(), name, primaryPath,
            contextPaths == null ? List.of() : List.copyOf(contextPaths),
            Instant.now(), false);
        return saveProject(project);
    }

    // --- Project deletion (operator action, docs: type-the-name confirmed) -----------------------

    /**
     * Counts everything {@link #deleteProject} would remove, WITHOUT removing anything. The Console
     * shows these numbers in the confirm step so the operator sees the real cost before typing the
     * project's name. An unknown id yields an empty summary rather than an error — the caller is
     * rendering a dialog, not executing.
     */
    public ProjectDeletionSummary projectContents(UUID projectId) {
        if (projectId == null || !root.projects.containsKey(projectId)) {
            return new ProjectDeletionSummary();
        }
        return scopeOf(projectId).summary();
    }

    /**
     * Removes a project and every record keyed to it: its runs and everything those runs own
     * (designs, task graphs, tasks, knowledge briefs, decisions, candidate archives, agent session
     * traces), its chats and transcripts, knowledge docs, BRD and BRD history, backlog stories and
     * iterations, uploaded source documents, change journal, criterion verification history, and
     * guided flows with their questions and proposals. Returns what was removed.
     *
     * <p><b>Nothing on disk is touched.</b> This class has no filesystem access beyond the
     * EclipseStore directory it was constructed with; the project's working tree is referenced only
     * as a {@code String} path on {@link Project}, which is removed along with the record. There is
     * deliberately no code path here that could delete a file.
     *
     * <p>The reach is computed as an id closure (project → stories/runs → graphs → tasks →
     * candidates) rather than by scanning for a projectId field, because most of these roots do not
     * carry one. Rows keyed to a dead project id are a slow leak that resurfaces as ghost data, so
     * the closure is deliberately exhaustive — and equally deliberately never widens past ids the
     * project actually owns.
     */
    public ProjectDeletionSummary deleteProject(UUID projectId) {
        if (projectId == null) {
            return new ProjectDeletionSummary();
        }
        try {
            return append(() -> {
                if (!root.projects.containsKey(projectId)) {
                    return new ProjectDeletionSummary();
                }
                ProjectScope scope = scopeOf(projectId);

                root.projects.remove(projectId);
                scope.runIds.forEach(root.runs::remove);
                scope.designIds.forEach(root.designs::remove);
                scope.taskGraphIds.forEach(root.taskGraphs::remove);
                scope.briefIds.forEach(root.briefs::remove);
                scope.decisionIds.forEach(root.decisions::remove);
                scope.candidateArchiveKeys.forEach(root.candidateArchives::remove);
                scope.agentSessionIds.forEach(root.agentSessions()::remove);
                scope.chatIds.forEach(root.chats()::remove);
                scope.chatMessageIds.forEach(root.chatMessages()::remove);
                scope.knowledgeDocIds.forEach(root.knowledgeDocs()::remove);
                root.brds().remove(projectId);
                root.brdRevisions().remove(projectId);
                scope.storyIds.forEach(root.stories()::remove);
                scope.iterationIds.forEach(root.iterations()::remove);
                scope.taskIds.forEach(root.tasks()::remove);
                scope.sourceDocumentIds.forEach(root.sourceDocuments()::remove);
                root.changeEvents().remove(projectId);
                scope.criterionIds.forEach(root.criterionVerifications()::remove);
                for (UUID flowId : scope.flowIds) {
                    root.guidedFlows().remove(flowId);
                    root.flowQuestions().remove(flowId);
                    root.flowProposals().remove(flowId);
                }
                // Discussions are keyed by question id, so they are not reachable from the flow id
                // and would survive the flow they belong to as unreferenced rows.
                scope.flowQuestionIds.forEach(root.flowDiscussions()::remove);
                root.autonomousDecisions().remove(projectId);
                // The project's rules go with it (author decision 2026-09-02). They used to be
                // files under the checkout, indexed here, and survived every deletion: a project
                // deleted and recreated four times against one folder was reopened with every
                // rule it had ever had, four times over.
                scope.ruleIds.forEach(root.guidelines::remove);
                // The "reopen where I was" bookmark, if it pointed here. Left behind it is a
                // pointer at a record that no longer exists — harmless at start-up, which falls
                // back to the default project, but it is a dead id in a durable store and it is
                // also what the Console offers as its pre-selected project.
                if (projectId.equals(root.lastProjectId())) {
                    root.setLastProjectId(null);
                }
                return scope.summary();
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete project " + projectId, e);
        }
    }

    /**
     * Removes every rule that belongs to no project this store knows: rules of projects deleted
     * before rules were deleted with them, and rules written before rules had an owner at all.
     *
     * <p>Run once at start-up. Both kinds are unreachable — no project ever lists another's rules,
     * and an ownerless rule is nobody's — so they are durable rows that no screen shows and no
     * prompt reads, which is the leak project deletion now closes going forward. Returns how many
     * were removed, for the log.
     */
    public int removeRulesOfDeletedProjects() {
        try {
            return append(() -> {
                List<UUID> orphaned = new ArrayList<>();
                for (LearnedGuideline rule : root.guidelines.values()) {
                    if (rule.projectId() == null || !root.projects.containsKey(rule.projectId())) {
                        orphaned.add(rule.id());
                    }
                }
                orphaned.forEach(root.guidelines::remove);
                return orphaned.size();
            }).get();
        } catch (Exception e) {
            log.warn("Could not sweep the rules of deleted projects: {}", e.toString());
            return 0;
        }
    }

    /** Every id and count a project owns. Read-only: computing it removes nothing. */
    private ProjectScope scopeOf(UUID projectId) {
        ProjectScope scope = new ProjectScope();

        // Roots that carry the projectId directly.
        for (LearnedGuideline rule : root.guidelines.values()) {
            if (projectId.equals(rule.projectId())) {
                scope.ruleIds.add(rule.id());
            }
        }
        for (Story story : root.stories().values()) {
            if (!projectId.equals(story.projectId())) {
                continue;
            }
            scope.storyIds.add(story.id());
            // A run recorded on this project's story IS this project's run, even when the run was
            // persisted before Run carried a projectId (those load with a null one).
            if (story.runIds() != null) {
                scope.runIds.addAll(story.runIds());
            }
            scope.runIds.add(story.originRunId());
            if (story.criterionIds() != null) {
                scope.criterionIds.addAll(story.criterionIds());
            }
        }
        for (Iteration iteration : root.iterations().values()) {
            if (projectId.equals(iteration.projectId())) {
                scope.iterationIds.add(iteration.id());
            }
        }
        for (ChatSession chat : root.chats().values()) {
            if (projectId.equals(chat.projectId())) {
                scope.chatIds.add(chat.id());
            }
        }
        scope.storyIds.remove(null);
        scope.iterationIds.remove(null);
        scope.chatIds.remove(null);
        for (ChatMessage message : root.chatMessages().values()) {
            if (scope.chatIds.contains(message.chatId())) {
                scope.chatMessageIds.add(message.id());
            }
        }
        for (KnowledgeDoc doc : root.knowledgeDocs().values()) {
            if (projectId.equals(doc.projectId())) {
                scope.knowledgeDocIds.add(doc.id());
            }
        }
        for (SourceDocument document : root.sourceDocuments().values()) {
            if (projectId.equals(document.projectId())) {
                scope.sourceDocumentIds.add(document.id());
            }
        }
        for (GuidedFlow flow : root.guidedFlows().values()) {
            if (projectId.equals(flow.projectId())) {
                scope.flowIds.add(flow.id());
            }
        }
        for (UUID flowId : scope.flowIds) {
            List<FlowQuestion> questions = root.flowQuestions().get(flowId);
            scope.flowQuestions += questions == null ? 0 : questions.size();
            if (questions != null) {
                for (FlowQuestion question : questions) {
                    scope.flowQuestionIds.add(question.id());
                    List<FlowDiscussionTurn> turns = root.flowDiscussions().get(question.id());
                    scope.flowDiscussionTurns += turns == null ? 0 : turns.size();
                }
            }
            List<FlowProposal> proposals = root.flowProposals().get(flowId);
            scope.flowProposals += proposals == null ? 0 : proposals.size();
        }
        scope.flowQuestionIds.remove(null);

        // BRD and its history. Every criterion id the document has EVER held is collected, history
        // included: criterion verifications are keyed by criterion id, so a criterion dropped from
        // the current revision still owns a verification list that would otherwise be orphaned.
        Brd brd = root.brds().get(projectId);
        if (brd != null) {
            scope.brds = 1;
            scope.requirements = brd.requirements() == null ? 0 : brd.requirements().size();
            collectCriterionIds(brd, scope.criterionIds);
        }
        List<BrdRevision> history = root.brdRevisions().get(projectId);
        if (history != null) {
            scope.brdRevisions = history.size();
            for (BrdRevision revision : history) {
                collectCriterionIds(revision.snapshot(), scope.criterionIds);
            }
        }
        List<ChangeEvent> journal = root.changeEvents().get(projectId);
        scope.changeEvents = journal == null ? 0 : journal.size();

        // Runs, then everything a run owns.
        for (Run run : root.runs.values()) {
            if (projectId.equals(run.projectId())) {
                scope.runIds.add(run.id());
            }
        }
        scope.runIds.remove(null);
        for (UUID runId : scope.runIds) {
            Run run = root.runs.get(runId);
            if (run == null) {
                continue;
            }
            scope.designIds.add(run.designId());
            scope.taskGraphIds.add(run.taskGraphId());
        }
        scope.designIds.remove(null);
        scope.taskGraphIds.remove(null);

        Map<UUID, Task> scopedTasks = new HashMap<>();
        for (UUID graphId : scope.taskGraphIds) {
            TaskGraph graph = root.taskGraphs.get(graphId);
            if (graph == null) {
                continue;
            }
            scope.designIds.add(graph.designId());
            if (graph.tasks() != null) {
                for (Task task : graph.tasks()) {
                    if (task.id() != null) {
                        scopedTasks.put(task.id(), task);
                    }
                }
            }
        }
        scope.designIds.remove(null);
        // The task index also holds tasks reachable only through a story (a run may be gone).
        for (Task task : root.tasks().values()) {
            if (task.id() != null && scope.storyIds.contains(task.storyId())) {
                scopedTasks.put(task.id(), task);
            }
        }
        scope.taskIds.addAll(scopedTasks.keySet());
        scope.taskIds.remove(null);

        Set<UUID> selectedCandidateIds = new HashSet<>();
        for (Task task : scopedTasks.values()) {
            scope.briefIds.add(task.knowledgeBriefId());
            if (task.criteria() != null) {
                for (AcceptanceCriterion criterion : task.criteria()) {
                    scope.criterionIds.add(criterion.id());
                }
            }
            scope.criterionIds.addAll(task.criterionIds());
            selectedCandidateIds.add(task.selectedCandidateId());
        }
        scope.briefIds.remove(null);
        scope.criterionIds.remove(null);
        selectedCandidateIds.remove(null);
        for (KnowledgeBrief brief : root.briefs.values()) {
            if (scope.taskIds.contains(brief.taskId())) {
                scope.briefIds.add(brief.id());
            }
        }
        for (Decision decision : root.decisions.values()) {
            if (scope.runIds.contains(decision.runId())) {
                scope.decisionIds.add(decision.id());
            }
        }

        // Bulky Lazy payloads. Dereferenced only here, on an explicit operator delete — never on a
        // read path. The candidate archive is keyed by candidate id in one writer and by TASK id in
        // another, so both conventions are matched before falling back to the payload's taskId.
        for (Map.Entry<UUID, Lazy<Object>> entry : root.agentSessions().entrySet()) {
            if (Lazy.get(entry.getValue()) instanceof AgentSessionRecord record
                && (scope.runIds.contains(record.runId())
                    || scope.taskIds.contains(record.taskId()))) {
                scope.agentSessionIds.add(entry.getKey());
            }
        }
        for (Map.Entry<UUID, Lazy<Object>> entry : root.candidateArchives.entrySet()) {
            UUID key = entry.getKey();
            if (scope.taskIds.contains(key) || selectedCandidateIds.contains(key)
                || (Lazy.get(entry.getValue()) instanceof CandidateSolution candidate
                    && scope.taskIds.contains(candidate.taskId()))) {
                scope.candidateArchiveKeys.add(key);
            }
        }

        // Verification history, by criterion id …
        for (UUID criterionId : scope.criterionIds) {
            List<CriterionVerification> verifications = root.criterionVerifications().get(criterionId);
            scope.verifications += verifications == null ? 0 : verifications.size();
        }
        // … plus any list whose entries point back at this project's runs, stories or tasks. Cheap
        // insurance for a criterion that is no longer reachable from the BRD or from a task graph.
        for (Map.Entry<UUID, List<CriterionVerification>> entry
                : root.criterionVerifications().entrySet()) {
            if (scope.criterionIds.contains(entry.getKey())) {
                continue;
            }
            List<CriterionVerification> verifications = entry.getValue();
            if (verifications == null || verifications.isEmpty()) {
                continue;
            }
            boolean ours = verifications.stream().anyMatch(v ->
                scope.runIds.contains(v.runId())
                    || scope.storyIds.contains(v.storyId())
                    || scope.taskIds.contains(v.taskId()));
            if (ours) {
                scope.criterionIds.add(entry.getKey());
                scope.verifications += verifications.size();
            }
        }
        scope.criterionIds.remove(null);
        return scope;
    }

    /** Adds every criterion id a BRD snapshot holds, across all of its requirements. */
    private static void collectCriterionIds(Brd brd, Set<UUID> into) {
        if (brd == null || brd.requirements() == null) {
            return;
        }
        for (BrdRequirement requirement : brd.requirements()) {
            if (requirement.criteria() == null) {
                continue;
            }
            for (AcceptanceCriterion criterion : requirement.criteria()) {
                into.add(criterion.id());
            }
        }
    }

    /** The id closure of one project — what {@link #deleteProject} removes, and nothing else. */
    private static final class ProjectScope {
        final Set<UUID> runIds = new HashSet<>();
        final Set<UUID> designIds = new HashSet<>();
        final Set<UUID> taskGraphIds = new HashSet<>();
        final Set<UUID> briefIds = new HashSet<>();
        final Set<UUID> taskIds = new HashSet<>();
        final Set<UUID> storyIds = new HashSet<>();
        final Set<UUID> iterationIds = new HashSet<>();
        final Set<UUID> chatIds = new HashSet<>();
        final Set<UUID> chatMessageIds = new HashSet<>();
        final Set<UUID> knowledgeDocIds = new HashSet<>();
        final Set<UUID> sourceDocumentIds = new HashSet<>();
        final Set<UUID> flowIds = new HashSet<>();
        /** Question ids, kept because discussions are keyed by them rather than by the flow. */
        final Set<UUID> flowQuestionIds = new HashSet<>();
        final Set<UUID> criterionIds = new HashSet<>();
        final Set<UUID> decisionIds = new HashSet<>();
        final Set<UUID> agentSessionIds = new HashSet<>();
        final Set<UUID> candidateArchiveKeys = new HashSet<>();
        final Set<UUID> ruleIds = new HashSet<>();
        int brds;
        int brdRevisions;
        int requirements;
        int changeEvents;
        int verifications;
        int flowQuestions;
        int flowProposals;
        int flowDiscussionTurns;

        ProjectDeletionSummary summary() {
            ProjectDeletionSummary summary = new ProjectDeletionSummary();
            summary.add(ProjectDeletionSummary.PROJECTS, 1);
            summary.add(ProjectDeletionSummary.REQUIREMENTS, requirements);
            summary.add(ProjectDeletionSummary.BRDS, brds);
            summary.add(ProjectDeletionSummary.BRD_REVISIONS, brdRevisions);
            summary.add(ProjectDeletionSummary.STORIES, storyIds.size());
            summary.add(ProjectDeletionSummary.ITERATIONS, iterationIds.size());
            summary.add(ProjectDeletionSummary.CHATS, chatIds.size());
            summary.add(ProjectDeletionSummary.CHAT_MESSAGES, chatMessageIds.size());
            summary.add(ProjectDeletionSummary.RUNS, runIds.size());
            summary.add(ProjectDeletionSummary.DESIGNS, designIds.size());
            summary.add(ProjectDeletionSummary.TASK_GRAPHS, taskGraphIds.size());
            summary.add(ProjectDeletionSummary.TASKS, taskIds.size());
            summary.add(ProjectDeletionSummary.KNOWLEDGE_BRIEFS, briefIds.size());
            summary.add(ProjectDeletionSummary.DECISIONS, decisionIds.size());
            summary.add(ProjectDeletionSummary.CANDIDATE_ARCHIVES, candidateArchiveKeys.size());
            summary.add(ProjectDeletionSummary.AGENT_SESSIONS, agentSessionIds.size());
            summary.add(ProjectDeletionSummary.KNOWLEDGE_DOCS, knowledgeDocIds.size());
            summary.add(ProjectDeletionSummary.SOURCE_DOCUMENTS, sourceDocumentIds.size());
            summary.add(ProjectDeletionSummary.CHANGE_EVENTS, changeEvents);
            summary.add(ProjectDeletionSummary.CRITERION_VERIFICATIONS, verifications);
            summary.add(ProjectDeletionSummary.GUIDED_FLOWS, flowIds.size());
            summary.add(ProjectDeletionSummary.FLOW_QUESTIONS, flowQuestions);
            summary.add(ProjectDeletionSummary.FLOW_PROPOSALS, flowProposals);
            summary.add(ProjectDeletionSummary.FLOW_DISCUSSION_TURNS, flowDiscussionTurns);
            summary.add(ProjectDeletionSummary.RULES, ruleIds.size());
            return summary;
        }
    }

    // --- Chats (Console v2, CONSOLE_DESIGN_V2.md §5) --------------------------------------------

    /** Persists (creates or updates) a chat session; blocks until durable. */
    public ChatSession saveChat(ChatSession chat) {
        try {
            return append(() -> {
                root.chats().put(chat.id(), chat);
                return chat;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist chat " + chat.id(), e);
        }
    }

    /** Non-archived chats of one project (null = all projects), newest first. */
    public List<ChatSession> listChats(UUID projectId) {
        return root.chats().values().stream()
            .filter(chat -> !chat.archived())
            .filter(chat -> projectId == null || projectId.equals(chat.projectId()))
            .sorted(Comparator.comparing(
                ChatSession::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    /** Appends a message with the chat's next sequence number; blocks until durable. */
    public ChatMessage appendChatMessage(ChatMessage message) {
        try {
            return append(() -> {
                int nextSeq = root.chatMessages().values().stream()
                    .filter(m -> message.chatId().equals(m.chatId()))
                    .mapToInt(ChatMessage::seq).max().orElse(-1) + 1;
                message.setSeq(nextSeq);
                root.chatMessages().put(message.id(), message);
                return message;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist chat message " + message.id(), e);
        }
    }

    /** A chat's transcript in sequence order. */
    /**
     * Removes one chat message. The single place anything is deleted outright rather than
     * tombstoned — a superseded coder reply is not a record of a decision, and leaving it would
     * put two answers to one question into the context the model reads on the next turn.
     */
    public void deleteChatMessage(UUID messageId) {
        if (messageId == null) {
            return;
        }
        try {
            append(() -> root.chatMessages().remove(messageId)).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete chat message " + messageId, e);
        }
    }

    public List<ChatMessage> chatTranscript(UUID chatId) {
        return root.chatMessages().values().stream()
            .filter(m -> chatId.equals(m.chatId()))
            .sorted(Comparator.comparingInt(ChatMessage::seq))
            .toList();
    }

    // --- Knowledge docs (store-first curated knowledge, 2026-07-14) ------------------------------

    /** Persists (creates or updates) a knowledge doc; blocks until durable. */
    public KnowledgeDoc saveKnowledgeDoc(KnowledgeDoc doc) {
        try {
            return append(() -> {
                root.knowledgeDocs().put(doc.id(), doc);
                return doc;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist knowledge doc " + doc.id(), e);
        }
    }

    /** A project's knowledge docs (null projectId = all), ACTIVE first then by slug. */
    public List<KnowledgeDoc> listKnowledgeDocs(UUID projectId) {
        return root.knowledgeDocs().values().stream()
            .filter(doc -> projectId == null || projectId.equals(doc.projectId()))
            .sorted(Comparator
                .comparing((KnowledgeDoc doc) -> !"ACTIVE".equals(doc.status()))
                .thenComparing(doc -> doc.slug() == null ? "" : doc.slug()))
            .toList();
    }

    public KnowledgeDoc getKnowledgeDoc(UUID id) {
        return id == null ? null : root.knowledgeDocs().get(id);
    }

    /** Removes a knowledge doc; blocks until durable. Returns whether it existed. */
    public boolean deleteKnowledgeDoc(UUID id) {
        try {
            return append(() -> root.knowledgeDocs().remove(id) != null).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete knowledge doc " + id, e);
        }
    }

    // --- BRD (per-project living requirements graph, 2026-07-24) --------------------------------

    /**
     * Persists (creates or updates) a project's BRD; blocks until durable. Keyed by projectId
     * (one BRD per project). Stamps {@code updatedAt} and bumps {@code revision} on every save.
     */
    public Brd saveBrd(Brd brd) {
        try {
            return append(() -> {
                brd.setRevision(brd.revision() + 1);
                brd.setUpdatedAt(Instant.now());
                root.brds().put(brd.projectId(), brd);
                // append() stores the CONTAINER (registers a new map entry) but the lazy storer
                // stops at already-known instances. storeDeep walks the whole BRD, so editing a
                // requirement or one of its criteria in place is persisted — callers no longer have
                // to remember to replace the list (see storeDeep).
                storeDeep(brd);
                return brd;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist BRD for project " + brd.projectId(), e);
        }
    }

    /**
     * Saves the BRD AND appends an attributed revision snapshot (the living-document history). The
     * snapshot is a deep copy so later edits never mutate history. {@code author} is
     * human|agent|extraction|restore; {@code summary} is a short human-readable note.
     */
    public Brd saveBrd(Brd brd, String author, String summary) {
        try {
            return append(() -> {
                brd.setRevision(brd.revision() + 1);
                brd.setUpdatedAt(Instant.now());
                root.brds().put(brd.projectId(), brd);
                storeDeep(brd);
                BrdRevision rev = new BrdRevision(UUID.randomUUID(), brd.projectId(),
                    brd.revision(), Instant.now(), author, summary, copyOf(brd));
                List<BrdRevision> history = root.brdRevisions()
                    .computeIfAbsent(brd.projectId(), k -> new java.util.ArrayList<>());
                history.add(rev);
                // Store the list itself: append() stores the CONTAINER map, but EclipseStore's lazy
                // storer won't persist a new element appended to an already-known list.
                storageManager.store(history);
                return brd;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist BRD revision for project " + brd.projectId(), e);
        }
    }

    /** A project's BRD revision history, oldest first. */
    public List<BrdRevision> listBrdRevisions(UUID projectId) {
        List<BrdRevision> history = projectId == null ? null : root.brdRevisions().get(projectId);
        return history == null ? List.of() : List.copyOf(history);
    }

    /** The BRD snapshot at a given revision, or null if that revision was never recorded. */
    public Brd brdRevisionSnapshot(UUID projectId, long revision) {
        for (BrdRevision r : listBrdRevisions(projectId)) {
            if (r.revision() == revision) {
                return r.snapshot();
            }
        }
        return null;
    }

    /**
     * Deep copy of a BRD — used for history snapshots (immune to later list reassignments) and to
     * publish a fresh instance onto the shared signal (whose {@code set()} dedups by {@code equals},
     * so it must receive a distinct object, never the mutated canonical one).
     */
    public static Brd copyOf(Brd brd) {
        List<BrdRequirement> reqs = new java.util.ArrayList<>();
        if (brd.requirements() != null) {
            for (BrdRequirement r : brd.requirements()) {
                reqs.add(copyOf(r));
            }
        }
        List<BrdEdge> edges = new java.util.ArrayList<>();
        if (brd.edges() != null) {
            for (BrdEdge e : brd.edges()) {
                edges.add(new BrdEdge(e.from(), e.to(), e.relation()));
            }
        }
        Brd copy = new Brd(brd.id(), brd.projectId(), brd.revision(), brd.title(),
            reqs, edges, brd.createdAt(), brd.updatedAt());
        // Hand-placed node positions travel with the copy, deep. Without this the graph published
        // onto the signal would arrive with every node unplaced, so the editor would auto-lay-out
        // nodes the operator had positioned — on every single edit.
        List<BrdNodePosition> positions = new java.util.ArrayList<>();
        for (BrdNodePosition p : brd.nodePositions()) {
            positions.add(new BrdNodePosition(p.requirementId(), p.x(), p.y()));
        }
        copy.setNodePositions(positions);
        return copy;
    }

    /**
     * Deep copy of ONE requirement, checks included.
     *
     * <p>Pulled out of {@link #copyOf(Brd)} rather than written twice, because the interesting part
     * is the list of things a copy must not forget: the fields added after the constructor (kind,
     * quality category, the document it came from, the content revision that decides whether
     * evidence is stale) and the checks, deep. Rebuilding a requirement through the seven-argument
     * constructor alone has already destroyed a document once, in the restore path.
     *
     * <p>Used on its own to hand a single requirement to the browser: the stored instance is live,
     * and giving it out would let a client-side edit reach the store with nobody saving anything.
     */
    public static BrdRequirement copyOf(BrdRequirement r) {
        if (r == null) {
            return null;
        }
        BrdRequirement copy = new BrdRequirement(r.id(), r.handle(), r.title(), r.text(),
            r.priority(), r.status(), r.category());
        copy.setKind(r.getKind());
        copy.setNfrCategory(r.nfrCategory());
        copy.setSourceRef(r.sourceRef());
        copy.setContentRevision(r.contentRevision());
        copy.setCriteria(copyCriteria(r.criteria()));
        return copy;
    }

    /** The BRD for a project, or null if none exists yet. */
    public Brd getBrd(UUID projectId) {
        return projectId == null ? null : root.brds().get(projectId);
    }

    /**
     * Returns the project's BRD, creating an empty one (persisted) if absent. The idempotent
     * entry point every consumer uses so a project always has a BRD to read and edit.
     */
    public Brd ensureBrd(UUID projectId) {
        Brd existing = getBrd(projectId);
        if (existing != null) {
            return existing;
        }
        Brd brd = new Brd(UUID.randomUUID(), projectId, 0, "Business Requirements",
            new java.util.ArrayList<>(), new java.util.ArrayList<>(), Instant.now(), Instant.now());
        return saveBrd(brd);
    }

    /**
     * One readable line per requirement-check this task answers for — the ref, the wording and the
     * test named as proving it.
     *
     * <p>Exists so a verification verdict can SAY what went unproven. "Verification failed" is a
     * red mark nobody can act on; "these two checks were claimed, each names a test, and no test
     * ran" is a fact somebody can fix. A scoped task references its criteria by id and owns no
     * copies of them, so the wording has to be resolved from the BRD here; an ENABLER task carries
     * its own list instead, and both are included.
     *
     * <p>An id that resolves to nothing is still listed, as the id: a claim pointing at a criterion
     * the BRD no longer holds is itself worth seeing, and dropping it would make the count lie.
     */
    public List<String> describeClaimedChecks(Task task) {
        if (task == null) {
            return List.of();
        }
        List<String> described = new ArrayList<>();
        for (UUID criterionId : task.criterionIds()) {
            if (criterionId == null) {
                continue;
            }
            String found = null;
            for (Brd brd : root.brds().values()) {
                found = describeIn(brd, criterionId);
                if (found != null) {
                    break;
                }
            }
            described.add(found != null ? found
                : criterionId + " (no criterion with this id is in any requirements document)");
        }
        if (task.criteria() != null) {
            for (AcceptanceCriterion criterion : task.criteria()) {
                if (criterion != null) {
                    described.add(describe(null, criterion));
                }
            }
        }
        return List.copyOf(described);
    }

    /** The description of a criterion within one requirements document, or null if absent. */
    private static String describeIn(Brd brd, UUID criterionId) {
        if (brd == null || brd.requirements() == null) {
            return null;
        }
        for (BrdRequirement requirement : brd.requirements()) {
            List<AcceptanceCriterion> criteria = requirement.criteria();
            for (int i = 0; i < criteria.size(); i++) {
                if (criterionId.equals(criteria.get(i).id())) {
                    return describe(requirement.handle() + ":C" + (i + 1), criteria.get(i));
                }
            }
        }
        return null;
    }

    private static String describe(String ref, AcceptanceCriterion criterion) {
        String test = criterion.testClassOrFile();
        return (ref == null ? "" : ref + " ")
            + criterion.text()
            + (test == null || test.isBlank() ? "  [names no test at all]" : "  [test: " + test + "]");
    }

    /** Deep copy of a criterion list, including the cached verification head. */
    private static List<AcceptanceCriterion> copyCriteria(List<AcceptanceCriterion> criteria) {
        List<AcceptanceCriterion> copies = new ArrayList<>();
        if (criteria != null) {
            for (AcceptanceCriterion c : criteria) {
                AcceptanceCriterion copy = new AcceptanceCriterion(c.id(), c.text(), c.testClassOrFile());
                copy.setTestRefOrigin(c.getTestRefOrigin());
                copy.setStatus(c.getStatus());
                copy.setVerification(c.getVerification());
                copy.setLastVerifiedRunId(c.lastVerifiedRunId());
                copy.setLastVerifiedCommit(c.lastVerifiedCommit());
                copy.setLastVerifiedAt(c.lastVerifiedAt());
                copy.setVerifiedAgainstContentRevision(c.verifiedAgainstContentRevision());
                copies.add(copy);
            }
        }
        return copies;
    }

    // --- Backlog: stories and iterations ------------------------------------------------------

    /**
     * Persists (creates or updates) a story; blocks until durable. The story instance is stored
     * explicitly as well as its container, because EclipseStore's lazy storer will not deep-store an
     * already-known object whose fields were reassigned.
     */
    public Story saveStory(Story story) {
        try {
            return append(() -> {
                story.setUpdatedAt(Instant.now());
                root.stories().put(story.id(), story);
                storeDeep(story);
                return story;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist story " + story.id(), e);
        }
    }

    public Story getStory(UUID id) {
        return id == null ? null : root.stories().get(id);
    }

    /**
     * Every story of a project, ordered by iteration sequence then story order. Cancelled stories
     * are INCLUDED — they are tombstones, not deletions, and the history must stay visible.
     */
    public List<Story> listStories(UUID projectId) {
        if (projectId == null) {
            return List.of();
        }
        return root.stories().values().stream()
            .filter(s -> projectId.equals(s.projectId()))
            .sorted(Comparator
                .comparingInt((Story s) -> iterationSeq(s.iterationId()))
                .thenComparingInt(Story::order)
                // Unscheduled stories all share iteration -1 and order 0, so without a tie-break
                // the result fell through to HashMap order over random UUIDs: a freshly planned
                // backlog listed itself differently on every read, and Triage shuffled under the
                // operator. Creation order is the one sequence that means anything before anyone
                // has ranked them.
                .thenComparing(s -> s.createdAt() == null ? Instant.EPOCH : s.createdAt())
                .thenComparing(s -> s.key() == null ? "" : s.key()))
            .toList();
    }

    /** Unscheduled stories are sorted after every iteration. */
    private int iterationSeq(UUID iterationId) {
        if (iterationId == null) {
            return Integer.MAX_VALUE;
        }
        Iteration iteration = root.iterations().get(iterationId);
        return iteration == null ? Integer.MAX_VALUE : iteration.seq();
    }

    /**
     * The next free story key for a project (S1, S2, …). Derived from the highest existing numeric
     * suffix rather than a counter, so it stays unique and stable across restarts without persisting
     * extra state — the same approach the BRD uses for requirement handles.
     */
    public String nextStoryKey(UUID projectId) {
        int highest = 0;
        for (Story s : listStories(projectId)) {
            String key = s.key();
            if (key != null && key.length() > 1 && (key.charAt(0) == 'S' || key.charAt(0) == 's')) {
                try {
                    highest = Math.max(highest, Integer.parseInt(key.substring(1)));
                } catch (NumberFormatException ignored) {
                    // a hand-edited key that is not S<n> simply does not participate
                }
            }
        }
        return "S" + (highest + 1);
    }

    public Iteration saveIteration(Iteration iteration) {
        try {
            return append(() -> {
                root.iterations().put(iteration.id(), iteration);
                storeDeep(iteration);
                return iteration;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist iteration " + iteration.id(), e);
        }
    }

    public Iteration getIteration(UUID id) {
        return id == null ? null : root.iterations().get(id);
    }

    /** A project's iterations in sequence order, closed ones included (tombstones stay visible). */
    public List<Iteration> listIterations(UUID projectId) {
        if (projectId == null) {
            return List.of();
        }
        return root.iterations().values().stream()
            .filter(i -> projectId.equals(i.projectId()))
            .sorted(Comparator.comparingInt(Iteration::seq))
            .toList();
    }

    // --- Task index ----------------------------------------------------------------------------

    /**
     * Indexes and persists a task. The index holds the SAME instance that lives in the run's
     * {@code TaskGraph}: EclipseStore persists by reference, so this costs one copy, not two.
     */
    public Task saveTask(Task task) {
        try {
            return append(() -> {
                root.tasks().put(task.id(), task);
                storeDeep(task);
                return task;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist task " + task.id(), e);
        }
    }

    /** Indexes a whole graph's tasks in one write — used when a task graph is first persisted. */
    public void indexTasks(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        try {
            append(() -> {
                for (Task t : tasks) {
                    root.tasks().put(t.id(), t);
                }
                return null;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to index " + tasks.size() + " tasks", e);
        }
    }

    public Task getTask(UUID id) {
        return id == null ? null : root.tasks().get(id);
    }

    /** The tasks belonging to a story, across every run that has attempted it. */
    public List<Task> storyTasks(UUID storyId) {
        if (storyId == null) {
            return List.of();
        }
        return root.tasks().values().stream()
            .filter(t -> storyId.equals(t.storyId()))
            .sorted(Comparator.comparing(Task::title, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
    }

    // --- Intake provenance ---------------------------------------------------------------------

    public SourceDocument saveSourceDocument(SourceDocument document) {
        try {
            return append(() -> {
                root.sourceDocuments().put(document.id(), document);
                storeDeep(document);
                return document;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist source document " + document.id(), e);
        }
    }

    /**
     * Permanently removes an uploaded document.
     *
     * <p>Deleted outright rather than tombstoned: an upload is raw input, not a record of a
     * decision, and a stale pile of half-relevant documents is exactly what makes the intake list
     * useless. Requirements already extracted from it keep their {@code SourceRef}, which then
     * points at a document that no longer exists — the provenance reads as "no longer available",
     * which is the truth, rather than being silently rewritten.
     */
    public void deleteSourceDocument(UUID id) {
        if (id == null) {
            return;
        }
        try {
            append(() -> root.sourceDocuments().remove(id)).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete source document " + id, e);
        }
    }

    public SourceDocument getSourceDocument(UUID id) {
        return id == null ? null : root.sourceDocuments().get(id);
    }

    /** Finds an already-ingested document by content hash, so the same upload is not re-extracted. */
    public SourceDocument findSourceDocumentBySha(UUID projectId, String sha256) {
        if (projectId == null || sha256 == null) {
            return null;
        }
        for (SourceDocument d : root.sourceDocuments().values()) {
            if (projectId.equals(d.projectId()) && sha256.equals(d.sha256())) {
                return d;
            }
        }
        return null;
    }

    public List<SourceDocument> listSourceDocuments(UUID projectId) {
        if (projectId == null) {
            return List.of();
        }
        return root.sourceDocuments().values().stream()
            .filter(d -> projectId.equals(d.projectId()))
            .sorted(Comparator.comparing(
                SourceDocument::uploadedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    // --- History: the append-only planning journal ---------------------------------------------

    /**
     * Appends an audit event. Nothing in the planning layer changes without one of these, which is
     * what makes the evolution of the project's intent replayable. Events are never mutated and
     * never removed.
     */
    public ChangeEvent recordChange(UUID projectId, String actor, ChangeEntityType entityType,
                                    UUID entityId, ChangeKind kind, String field,
                                    String before, String after, String summary, UUID runId) {
        ChangeEvent event = new ChangeEvent(UUID.randomUUID(), projectId, Instant.now(), actor,
            entityType, entityId, kind, field, before, after, summary, runId);
        try {
            return append(() -> {
                List<ChangeEvent> journal = root.changeEvents()
                    .computeIfAbsent(projectId, k -> new ArrayList<>());
                journal.add(event);
                // Store the list itself: append() stores the CONTAINER map, but the lazy storer will
                // not persist a new element appended to an already-known list.
                storageManager.store(journal);
                return event;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to record change event for project " + projectId, e);
        }
    }

    /** Convenience for the common "something changed state" event. */
    public ChangeEvent recordChange(UUID projectId, String actor, ChangeEntityType entityType,
                                    UUID entityId, ChangeKind kind, String summary) {
        return recordChange(projectId, actor, entityType, entityId, kind, null, null, null, summary, null);
    }

    /** A project's audit journal, oldest first. */
    public List<ChangeEvent> listChangeEvents(UUID projectId) {
        List<ChangeEvent> journal = projectId == null ? null : root.changeEvents().get(projectId);
        return journal == null ? List.of() : List.copyOf(journal);
    }

    /** The audit trail of one requirement, criterion, story, iteration or task. */
    public List<ChangeEvent> changeHistory(UUID projectId, UUID entityId) {
        if (entityId == null) {
            return List.of();
        }
        return listChangeEvents(projectId).stream()
            .filter(e -> entityId.equals(e.entityId()))
            .toList();
    }

    /**
     * Appends a criterion verification result and refreshes the criterion's cached head. The journal
     * is the truth; the fields on {@link AcceptanceCriterion} exist only so the UI can render without
     * walking history.
     */
    public CriterionVerification recordVerification(CriterionVerification verification) {
        try {
            return append(() -> {
                List<CriterionVerification> history = root.criterionVerifications()
                    .computeIfAbsent(verification.criterionId(), k -> new ArrayList<>());
                history.add(verification);
                storageManager.store(history);
                return verification;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to record verification for criterion " + verification.criterionId(), e);
        }
    }

    /** Every recorded verification of a criterion, oldest first — including regressions. */
    public List<CriterionVerification> listVerifications(UUID criterionId) {
        List<CriterionVerification> history =
            criterionId == null ? null : root.criterionVerifications().get(criterionId);
        return history == null ? List.of() : List.copyOf(history);
    }

    /**
     * Deep copy of a story — used to publish a fresh instance onto the shared backlog signal, whose
     * {@code set()} dedups by {@code equals}, so it must receive a distinct object and never the
     * mutated canonical one.
     */
    public static Story copyOf(Story story) {
        Story copy = new Story(story.id(), story.projectId(), story.key(), story.kind(),
            story.title(), story.narrative(), story.state(),
            new ArrayList<>(story.requirementIds()), new ArrayList<>(story.criterionIds()),
            story.iterationId(), story.order(), story.origin(), story.originRunId(),
            story.rationale(), story.author(), new ArrayList<>(story.runIds()),
            story.deliveredCommit(), story.integrationCommit(), story.prNumber(), story.prUrl(),
            story.createdAt(), story.updatedAt());
        // The fields that are NOT in the constructor. A copy that forgets one fails invisibly: the
        // store stays right and the board simply never learns the fact. That is exactly what
        // happened the first time this was run — every story reached the browser carrying no
        // dependencies at all, so the board showed identical "nothing is building it yet" cards,
        // which is the very failure being fixed. Anything added to Story from now on is added here.
        copy.setDependsOnStoryIds(new ArrayList<>(story.declaredDependsOn()));
        copy.setDiscoveredDependsOnStoryIds(new ArrayList<>(story.discoveredDependsOn()));
        copy.setDependencyRetries(story.dependencyRetries());
        copy.setWaitingReason(story.waitingReason());
        copy.setWorkersPerTask(story.workersPerTask());
        copy.setAcceptedBy(story.acceptedBy());
        return copy;
    }

    // --- Guided flows (docs/GUIDED_FLOWS_DESIGN.md) ---------------------------------------------

    /** Persists a flow; blocks until durable. Stored deeply so in-place edits are captured. */
    public GuidedFlow saveGuidedFlow(GuidedFlow flow) {
        try {
            return append(() -> {
                flow.setUpdatedAt(Instant.now());
                root.guidedFlows().put(flow.id(), flow);
                storeDeep(flow);
                return flow;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist guided flow " + flow.id(), e);
        }
    }

    public GuidedFlow getGuidedFlow(UUID id) {
        return id == null ? null : root.guidedFlows().get(id);
    }

    /** A project's flows, newest first — the wizard re-opens the most recent one. */
    public List<GuidedFlow> listGuidedFlows(UUID projectId) {
        if (projectId == null) {
            return List.of();
        }
        return root.guidedFlows().values().stream()
            .filter(f -> projectId.equals(f.projectId()))
            .sorted(Comparator.comparing(
                GuidedFlow::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    /**
     * Replaces a flow's questions wholesale.
     *
     * <p>Wholesale because a round of questions is produced as a set: merging would leave answers
     * attached to questions the agent no longer intends to ask.
     */
    public void saveFlowQuestions(UUID flowId, List<FlowQuestion> questions) {
        try {
            append(() -> {
                List<FlowQuestion> stored = new ArrayList<>(questions == null ? List.of() : questions);
                root.flowQuestions().put(flowId, stored);
                storageManager.store(stored);
                return null;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist questions for flow " + flowId, e);
        }
    }

    public List<FlowQuestion> listFlowQuestions(UUID flowId) {
        List<FlowQuestion> questions = flowId == null ? null : root.flowQuestions().get(flowId);
        return questions == null ? List.of() : List.copyOf(questions);
    }

    /** Replaces a flow's proposals wholesale — same reasoning as the questions. */
    public void saveFlowProposals(UUID flowId, List<FlowProposal> proposals) {
        try {
            append(() -> {
                List<FlowProposal> stored = new ArrayList<>(proposals == null ? List.of() : proposals);
                root.flowProposals().put(flowId, stored);
                storageManager.store(stored);
                return null;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist proposals for flow " + flowId, e);
        }
    }

    public List<FlowProposal> listFlowProposals(UUID flowId) {
        List<FlowProposal> proposals = flowId == null ? null : root.flowProposals().get(flowId);
        return proposals == null ? List.of() : List.copyOf(proposals);
    }

    /**
     * Adds one turn to a question's discussion, keeping the order it was said in.
     *
     * <p>Append, not replace — unlike questions and proposals, which are produced as a set and
     * therefore rewritten wholesale. A discussion grows a turn at a time from two directions (the
     * operator types, the analyst answers on a worker thread), and read-modify-write from the caller
     * would lose whichever of the two lost the race. The append happens inside the store's single
     * writer, so the ordering is the store's to guarantee rather than the caller's.
     *
     * @return the whole discussion as it now stands, so the caller need not re-read it
     */
    public List<FlowDiscussionTurn> appendFlowDiscussionTurn(UUID questionId,
                                                             FlowDiscussionTurn turn) {
        if (questionId == null || turn == null) {
            return List.of();
        }
        try {
            return append(() -> {
                List<FlowDiscussionTurn> stored = root.flowDiscussions()
                    .computeIfAbsent(questionId, k -> new ArrayList<>());
                stored.add(turn);
                storageManager.store(stored);
                return List.copyOf(stored);
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to persist a discussion turn for question "
                + questionId, e);
        }
    }

    /** One question's discussion, oldest turn first. Empty for a question never discussed. */
    public List<FlowDiscussionTurn> listFlowDiscussion(UUID questionId) {
        List<FlowDiscussionTurn> turns =
            questionId == null ? null : root.flowDiscussions().get(questionId);
        return turns == null ? List.of() : List.copyOf(turns);
    }

    /**
     * Forgets every discussion held about the given questions.
     *
     * <p>Called when a run's questions are thrown away: a discussion explains a question that no
     * longer exists, and keeping it would leave rows nothing can reach or delete.
     */
    // --- What the machine decided while nobody was watching --------------------------------------

    /**
     * Appends one decision the machine took on the operator's behalf. Append-only: a decision is a
     * fact about what happened, and rewriting one would make the morning's reading a work of
     * fiction.
     */
    public void recordAutonomousDecision(AutonomousDecision decision) {
        if (decision == null || decision.projectId() == null) {
            return;
        }
        try {
            append(() -> {
                List<AutonomousDecision> existing =
                    root.autonomousDecisions().get(decision.projectId());
                List<AutonomousDecision> updated = new ArrayList<>(
                    existing == null ? List.of() : existing);
                updated.add(decision);
                root.autonomousDecisions().put(decision.projectId(), updated);
                storageManager.store(updated);
                storageManager.store(root.autonomousDecisions());
                return null;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to record an autonomous decision for project "
                + decision.projectId(), e);
        }
    }

    /** Everything the machine has ever decided for this project, oldest first. */
    public List<AutonomousDecision> listAutonomousDecisions(UUID projectId) {
        List<AutonomousDecision> decisions =
            projectId == null ? null : root.autonomousDecisions().get(projectId);
        return decisions == null ? List.of() : List.copyOf(decisions);
    }

    public void deleteFlowDiscussions(List<UUID> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            return;
        }
        try {
            append(() -> {
                for (UUID questionId : questionIds) {
                    root.flowDiscussions().remove(questionId);
                }
                return null;
            }).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete discussions for " + questionIds.size()
                + " question(s)", e);
        }
    }

    /**
     * Deep copy of a flow — the shared-signal contract again: {@code set()} dedups by
     * {@code equals}, so publishing the mutated canonical instance would compare equal to the
     * retained value and the wizard would sit on stale progress with no error anywhere.
     *
     * <p><b>Every field of every document, and this was not always true.</b> This copy was written
     * when a {@code FlowDocument} was an id and a note, and it silently kept copying only those two
     * as three more were added: when it was last read this method dropped {@code analysedAt},
     * {@code excluded} and {@code technical} on the floor. A copy is what every wizard renders, so
     * the effect was that ticking a document — as read next time, or as saying how the system must
     * be built — wrote the flag to disk correctly and then came straight back to the screen as
     * false, with no error anywhere. The tick appeared not to work, and reloading the page fixed
     * it, which is the signature of exactly this.
     */
    public static GuidedFlow copyOf(GuidedFlow flow) {
        List<FlowDocument> documents = new ArrayList<>();
        for (FlowDocument document : flow.documents()) {
            documents.add(new FlowDocument(document.documentId(), document.notes(),
                document.analysedAt(), document.excluded(), document.technical()));
        }
        return new GuidedFlow(flow.id(), flow.projectId(), flow.kind(), flow.state(), flow.step(),
            flow.totalSteps(), flow.stepLabel(), flow.error(), documents,
            flow.createdAt(), flow.updatedAt());
    }

    /**
     * Deep copy of a task — same shared-signal contract as {@link #copyOf(Story)}. The task now
     * travels on the backlog signal as itself rather than as a projection, so the copy has to be
     * genuinely deep: the three sets and the criteria list are rebuilt, or a mutation of the
     * canonical task would show through and the signal would dedup the publish away.
     */
    public static Task copyOf(Task task) {
        Task copy = new Task(task.id(), task.revision(), task.title(), task.instructions(),
            task.writeSet() == null ? new java.util.LinkedHashSet<>()
                : new java.util.LinkedHashSet<>(task.writeSet()),
            task.readSet() == null ? new java.util.LinkedHashSet<>()
                : new java.util.LinkedHashSet<>(task.readSet()),
            task.criteria() == null ? new ArrayList<>() : new ArrayList<>(task.criteria()),
            task.acceptanceTestDir(), task.knowledgeBriefId(), task.budget(), task.swarmPolicy(),
            task.state(),
            task.requirementIds() == null ? new java.util.LinkedHashSet<>()
                : new java.util.LinkedHashSet<>(task.requirementIds()));
        copy.setStoryId(task.storyId());
        copy.setCriterionIds(new java.util.LinkedHashSet<>(task.criterionIds()));
        copy.setSelectedCandidateId(task.selectedCandidateId());
        copy.setCommitSha(task.commitSha());
        copy.setAuthoredTests(task.authoredTests());
        copy.setChecksAlreadyProved(task.checksAlreadyProved());
        return copy;
    }

    /** Deep copy of an iteration — same shared-signal contract as {@link #copyOf(Story)}. */
    public static Iteration copyOf(Iteration iteration) {
        return new Iteration(iteration.id(), iteration.projectId(), iteration.name(),
            iteration.goal(), iteration.seq(), iteration.state(),
            iteration.createdAt(), iteration.closedAt());
    }

    /**
     * Writes a consistent copy of the whole store into {@code target}, which can then be opened
     * with {@code new ArtifactStore(target)} like any other store, while this one stays open and in
     * use.
     *
     * <p><b>Why this exists (2026-09-25).</b> The live end-to-end harness spends 20 to 40 minutes
     * on a local model reaching the point where workers are dispatched, and that front half is
     * nearly the same every run. It saves the store at that point so later runs can start there.
     * Copying the storage directory's files is not a way to do that: EclipseStore appends to its
     * data and transaction files from its own channel threads, and a file copied halfway through
     * an append is a store that either will not open or opens with half a write in it.
     *
     * <p><b>How it stays consistent.</b> Two things, one on top of the other:
     * <ul>
     *   <li>It runs on this store's single writer thread, so it is queued BEHIND every write
     *       already submitted through this class — they are all in the copy — and none submitted
     *       after it can interleave with it.</li>
     *   <li>The copy itself is EclipseStore's own full backup
     *       ({@code StorageConnection.issueFullBackup}), which the storage channels execute as one
     *       of their own tasks, so a commit is either wholly in it or wholly absent, whichever
     *       thread stored it. It exports the channel files and the type dictionary in the default
     *       layout, which is why the target opens as an ordinary store.</li>
     * </ul>
     *
     * @param target an absent or empty directory; EclipseStore refuses a non-empty one, and so does
     *               this method, rather than mixing two stores' files
     * @throws IllegalStateException when the target is not empty or the backup failed
     */
    public void backupTo(Path target) {
        String[] existing = target.toFile().list();
        if (existing != null && existing.length > 0) {
            throw new IllegalStateException("will not back the store up into " + target
                + ": it already holds " + existing.length + " entr(y/ies)");
        }
        Path absolute = target.toAbsolutePath();
        try {
            writerThread.submit(() -> {
                storageManager.issueFullBackup(
                    NioFileSystem.New(absolute.getFileSystem()).ensureDirectory(absolute));
                return null;
            }).get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("the store could not be backed up into " + target
                + ": " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing the store up", e);
        }
    }

    /**
     * Closes the store, but only after every write already queued has reached disk.
     *
     * <p><b>The wait is the whole point.</b> {@code shutdown()} refuses new work and returns
     * immediately; it does not wait for the work already queued. Closing the storage manager on top
     * of a writer thread that is still running one produced, deterministically:
     *
     * <pre>
     * org.eclipse.serializer.persistence.exceptions.PersistenceExceptionTransfer
     *   at EmbeddedStorageBinaryTarget$Default.write(EmbeddedStorageBinaryTarget.java:123)
     *   at BinaryStorer$Default.commit(BinaryStorer.java:634)
     * Caused by: org.eclipse.store.storage.exceptions.StorageException: Problem in channel #0
     * Caused by: java.lang.NullPointerException: Cannot invoke
     *   "StorageLiveDataFile$Default.needsRetirement(StorageDataFileEvaluator)"
     *   because "this.headFile" is null
     * </pre>
     *
     * <p>…on the {@code swarmcoder-artifact-store-writer} thread, for every write still in the
     * queue. Nobody saw it: the exception went into a {@link Future} the caller had already
     * discarded, and the only symptom was a field that had been written, was true in memory, and
     * was absent after a restart — a park's reason, a heartbeat stamp — sometimes, depending purely
     * on how much of the queue had drained before the close landed. That is the whole of the
     * "persistence is flaky" report of 2026-09-03.
     *
     * <p>If the queue cannot be drained inside {@link #CLOSE_DRAIN_SECONDS}, the remaining writes
     * are abandoned and that is said at ERROR. A lost write is reported, never swallowed.
     */
    @Override
    public void close() throws Exception {
        writerThread.shutdown();
        if (!writerThread.awaitTermination(CLOSE_DRAIN_SECONDS, TimeUnit.SECONDS)) {
            int abandoned = writerThread.shutdownNow().size();
            log.error("The artifact store is being closed with {} write(s) still queued after "
                + "waiting {}s for them. Those writes did NOT reach disk.",
                abandoned, CLOSE_DRAIN_SECONDS);
        }
        storageManager.close();
    }
}
