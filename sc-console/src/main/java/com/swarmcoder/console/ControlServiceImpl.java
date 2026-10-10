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

import com.swarmcoder.console.api.ControlService;
import com.zeroz4j.server.ClientVisibleException;
import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import com.swarmcoder.console.api.BudgetsDto;
import com.swarmcoder.console.api.GuidelineDto;
import com.swarmcoder.console.api.ProjectDto;
import com.swarmcoder.console.api.ProjectList;
import com.swarmcoder.console.api.ProjectSignals;
import com.swarmcoder.console.api.RoleEntryDto;
import com.swarmcoder.domain.AutonomousDecision;
import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.GuidelineStatus;
import com.swarmcoder.domain.KnowledgeDoc;
import com.swarmcoder.domain.LearnedGuideline;
import com.swarmcoder.domain.Project;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.store.ProjectDeletionSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.serializer.reference.Lazy;

@ApplicationScoped
public class ControlServiceImpl implements ControlService {

    private static final Logger log = LoggerFactory.getLogger(ControlServiceImpl.class);

    @Override
    public String submitIntake(String goal, String kind) {
        return ConsoleContext.get().startRun(goal, kind).toString();
    }

    @Override
    public void approveRun(String runId) {
        ConsoleContext.get().approveRun(UUID.fromString(runId));
    }

    @Override
    public void rejectRun(String runId) {
        ConsoleContext.get().rejectRun(UUID.fromString(runId));
    }

    @Override
    public String integratedDiff(String runId) {
        UUID id;
        try {
            id = UUID.fromString(runId);
        } catch (IllegalArgumentException e) {
            return "";
        }
        var store = ConsoleContext.get().store();
        Run run = store.root().runs.get(id);
        if (run == null || run.taskGraphId() == null) {
            return "";
        }
        TaskGraph graph = store.root().taskGraphs.get(run.taskGraphId());
        if (graph == null || graph.tasks() == null) {
            return "";
        }
        Set<UUID> taskIds = new HashSet<>();
        graph.tasks().forEach(task -> taskIds.add(task.id()));
        Map<UUID, String> byTask = new HashMap<>();
        for (var lazy : store.root().candidateArchives.values()) {
            if (Lazy.get(lazy)
                    instanceof CandidateSolution candidate
                    && candidate.state() == CandidateState.SELECTED
                    && taskIds.contains(candidate.taskId())) {
                byTask.put(candidate.taskId(), candidate.diffUnified());
            }
        }
        // Concatenate winners in task order — disjoint write sets keep them non-overlapping.
        StringBuilder sb = new StringBuilder();
        for (Task task : graph.tasks()) {
            String diff = byTask.get(task.id());
            if (diff != null && !diff.isBlank()) {
                sb.append(diff);
                if (!diff.endsWith("\n")) {
                    sb.append('\n');
                }
            }
        }
        // Every task's winning diff in one string: a large run clears 4 MB, and that closes the
        // connection rather than failing the call (WireBudget).
        return WireBudget.clamp(sb.toString(), "This run's combined diff");
    }

    @Override
    public List<Decision> decisions() {
        return ConsoleContext.get().store().root().decisions.values().stream()
            .sorted(Comparator.comparing(decision -> decision.createdAt() == null
                ? Instant.EPOCH : decision.createdAt(), Comparator.reverseOrder()))
            .toList();
    }

    @Override
    public String settingsYaml() {
        return ConsoleContext.get().readSettings();
    }

    @Override
    public String saveSettingsYaml(String yaml) {
        try {
            return ConsoleContext.get().writeSettings(yaml);
        } catch (Exception e) {
            return "save failed: " + e.getMessage();
        }
    }

    @Override
    public List<ProjectDto> projects() {
        // Publish as well as return: the rail is driven by ProjectSignals.CURRENT, so any read
        // also refreshes what every open client sees. ProjectPublisher is the single builder.
        ProjectList list = ProjectPublisher.build();
        ProjectSignals.CURRENT.set(list);
        return list.getProjects();
    }

    @Override
    public String createProject(String name, String primaryPath, String contextPathsCsv) {
        try {
            List<String> contextPaths = contextPathsCsv == null || contextPathsCsv.isBlank()
                ? List.of()
                : Arrays.stream(contextPathsCsv.split(",")).map(String::trim)
                    .filter(s -> !s.isEmpty()).toList();
            return ConsoleContext.get().createProject(name, primaryPath, contextPaths).id().toString();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * The on-ramp. Both calls delegate to the bridge sc-app wires in, because detection lives in
     * sc-verify and the console does not depend on it.
     */
    @Override
    public com.swarmcoder.console.api.BuildContractDto detectBuildContract(String primaryPath,
                                                                          boolean probe) {
        try {
            return ConsoleContext.get().buildContracts().detect(primaryPath, probe);
        } catch (Exception e) {
            log.warn("Build-contract detection failed for {}: {}", primaryPath, e.toString());
            return null;
        }
    }

    @Override
    public String saveBuildContract(String primaryPath, String yaml, boolean overwrite) {
        try {
            return ConsoleContext.get().buildContracts().save(primaryPath, yaml, overwrite);
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /**
     * Moves every Console view to another project.
     *
     * <p>Both refusals are {@link ClientVisibleException}s: since 0.7.0 an ordinary exception's
     * message is replaced by a reference code before it reaches the browser, and "that project is
     * gone" is exactly the kind of thing the operator has to be told in words. A malformed id is
     * still a plain refusal, because a browser sending one is a defect, not a mistake anybody made.
     */
    @Override
    public void switchProject(String projectId) {
        UUID id = parseId(projectId);
        if (id == null) {
            throw new ClientVisibleException("That is not a project id: " + projectId);
        }
        try {
            ConsoleContext.get().switchProject(id);
        } catch (IllegalArgumentException e) {
            throw new ClientVisibleException(
                "That project no longer exists. Pick another one from the project menu.");
        }
    }

    @Override
    public String projectDeletionPreview(String projectId) {
        UUID id = parseId(projectId);
        if (id == null) {
            return "error: not a project id: " + projectId;
        }
        ConsoleContext context = ConsoleContext.get();
        if (context.store().getProject(id) == null) {
            return "error: no such project " + projectId;
        }
        ProjectDeletionSummary counts = context.store().projectContents(id);
        return "This permanently deletes the project's requirements (" + counts.requirements()
            + "), backlog stories (" + counts.stories()
            + "), chats (" + counts.chats()
            + "), runs (" + counts.runs()
            + "), uploaded documents (" + counts.sourceDocuments()
            + ") and all history.";
    }

    @Override
    public String deleteProject(String projectId, String confirmationName) {
        ConsoleContext.refuseIfWatching("delete a project");
        UUID id = parseId(projectId);
        if (id == null) {
            return "error: not a project id: " + projectId;
        }
        ConsoleContext context = ConsoleContext.get();
        Project project = context.store().getProject(id);
        if (project == null) {
            return "error: no such project " + projectId;
        }
        // Re-checked HERE, not only in the dialog. The typed name is the entire guard against an
        // unrecoverable action, and a guard that lives only in the browser is a suggestion: anything
        // that speaks the protocol skips it. An unnamed project is confirmed by its id, so a blank
        // name cannot make a project either undeletable or deletable by typing nothing.
        String expected = confirmationTokenFor(project);
        String typed = confirmationName == null ? "" : confirmationName.trim();
        if (!expected.equals(typed)) {
            return "error: the typed name does not match this project — nothing was deleted";
        }

        boolean wasCurrent = id.equals(currentProjectIdOrNull(context));
        // Ends what this project left running BEFORE its records disappear: nothing of a deleted
        // project may keep running or keep asking questions, and the engine/worktree teardown still
        // needs project.primaryPath() (and the project object itself), which store().deleteProject
        // is about to make unreachable. See ConsoleContext.teardownProject.
        context.teardownProject(project);
        ProjectDeletionSummary removed;
        try {
            removed = context.store().deleteProject(id);
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
        // The path is logged precisely so the record shows what was NOT touched: deletion removes
        // SwarmCoder's records, never the working tree.
        log.info("Deleted project '{}' ({}) — removed {}. The folder on disk at {} was not touched.",
            project.name(), id, removed, project.primaryPath());

        // Whatever happens next, the operator has to say which project they want. A deletion used
        // to OPEN the next project in the list: the screen filled with another project's
        // requirements, backlog and runs, which looks exactly like the project that was just
        // destroyed still being there. The obvious response to that is to delete it again — and
        // that second deletion lands on a project nobody meant to touch.
        context.requireProjectChoice();
        if (wasCurrent) {
            // The machinery still has to point somewhere real: intake, chat and the BRD all
            // resolve through the current project, and a pointer at a deleted record fails in
            // places that have nothing to do with projects. So it is MOVED — quietly, without
            // recording it as a choice, so the picker still asks.
            List<Project> remaining = context.listProjects();
            if (remaining.isEmpty()) {
                log.warn("Deleted the last project — no project is current; create one to continue");
                // Nothing is scoped to anything any more, and the shell has to be told: without
                // this its counts and its stages go on describing the project that is gone.
                ReadinessPublisher.publish(context);
            } else {
                context.moveCurrentProjectQuietly(remaining.get(0).id());
            }
        }
        ProjectPublisher.publish();
        return "";
    }

    /**
     * What the operator has to type to confirm: the project's name, or its id when it has no name.
     */
    static String confirmationTokenFor(Project project) {
        String name = project.name() == null ? "" : project.name().trim();
        return name.isEmpty() ? String.valueOf(project.id()) : name;
    }

    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (Exception e) {
            return null;
        }
    }

    /** The current project id, or null when the bridge cannot answer (e.g. it was just deleted). */
    private static UUID currentProjectIdOrNull(ConsoleContext context) {
        try {
            return context.currentProjectId();
        } catch (Exception e) {
            return null;
        }
    }

    // --- Typed settings forms (design §7) — delegated to the sc-app ConfigForms bridge ----------

    @Override
    public List<RoleEntryDto> globalRoles() {
        var forms = ConsoleContext.get().configForms();
        return forms == null ? List.of() : forms.globalRoles();
    }

    @Override
    public String saveGlobalRoles(List<RoleEntryDto> roles) {
        var forms = ConsoleContext.get().configForms();
        return forms == null ? "error: settings forms not wired" : forms.saveGlobalRoles(roles);
    }

    @Override
    public BudgetsDto globalBudgets() {
        var forms = ConsoleContext.get().configForms();
        return forms == null ? new BudgetsDto() : forms.budgets();
    }

    @Override
    public String saveGlobalBudgets(BudgetsDto budgets) {
        var forms = ConsoleContext.get().configForms();
        return forms == null ? "error: settings forms not wired" : forms.saveBudgets(budgets);
    }

    @Override
    public List<RoleEntryDto> projectRoles(String projectId) {
        var forms = ConsoleContext.get().configForms();
        return forms == null ? List.of() : forms.projectRoles(projectId);
    }

    @Override
    public int projectWorkersPerTask(String projectId) {
        var forms = ConsoleContext.get().configForms();
        return forms == null ? 0 : forms.projectWorkersPerTask(projectId);
    }

    @Override
    public String saveProjectConfig(String projectId, String contextPathsCsv,
                                    List<RoleEntryDto> roles, int workersPerTask) {
        var forms = ConsoleContext.get().configForms();
        if (forms == null) {
            return "error: settings forms not wired";
        }
        String result = forms.saveProjectConfig(projectId, contextPathsCsv, roles, workersPerTask);
        if (result == null || result.isEmpty()) {
            // The rail shows each project's context folders; republish so it reflects the edit.
            ProjectPublisher.publish();
        ReadinessPublisher.publish(ConsoleContext.get());
        }
        return result;
    }

    // --- Knowledge curation (store-first, scoped to the current project) -------------------------

    @Override
    public List<KnowledgeDoc> knowledgeDocs() {
        ConsoleContext context = ConsoleContext.get();
        return context.store().listKnowledgeDocs(context.currentProjectId());
    }

    @Override
    public String saveKnowledgeDoc(KnowledgeDoc incoming) {
        try {
            ConsoleContext context = ConsoleContext.get();
            KnowledgeDoc existing = incoming.id() == null
                ? null : context.store().getKnowledgeDoc(incoming.id());
            KnowledgeDoc doc = existing != null ? existing
                : new KnowledgeDoc(UUID.randomUUID(),
                    context.currentProjectId(), null, null, null, "ACTIVE", "human",
                    Instant.now(), Instant.now());
            doc.setSlug(incoming.slug() == null || incoming.slug().isBlank()
                ? slugify(incoming.title()) : slugify(incoming.slug()));
            doc.setTitle(incoming.title());
            doc.setBody(incoming.body());
            doc.setUpdatedAt(Instant.now());
            context.store().saveKnowledgeDoc(doc);
            return doc.id().toString();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    @Override
    public String acceptKnowledgeDoc(String docId) {
        try {
            ConsoleContext context = ConsoleContext.get();
            KnowledgeDoc doc = context.store().getKnowledgeDoc(UUID.fromString(docId));
            if (doc == null) {
                return "error: no such knowledge doc " + docId;
            }
            doc.setStatus("ACTIVE");
            doc.setUpdatedAt(Instant.now());
            context.store().saveKnowledgeDoc(doc);
            return "";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    @Override
    public String deleteKnowledgeDoc(String docId) {
        try {
            return ConsoleContext.get().store().deleteKnowledgeDoc(UUID.fromString(docId))
                ? "" : "error: no such knowledge doc " + docId;
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    // --- Researcher ------------------------------------------------------------------------------

    @Override
    public String startResearch(String topic) {
        var researcher = ConsoleContext.get().researcher();
        return researcher == null ? "error: researcher not wired" : researcher.start(topic);
    }

    @Override
    public String researchStatus() {
        var researcher = ConsoleContext.get().researcher();
        return researcher == null ? "idle" : researcher.status();
    }

    private static String slugify(String text) {
        String slug = (text == null ? "" : text).toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        return slug.isBlank() ? "doc-" + UUID.randomUUID().toString().substring(0, 8) : slug;
    }

    @Override
    public List<GuidelineDto> guidelines() {
        // The current project's rules only. A rule belongs to a project (2026-09-02); listing the
        // whole store showed every project's rules on every project's screen. With no current
        // project known — a console wired without projects — everything is listed, as before.
        UUID projectId = ConsoleContext.get().currentProjectId();
        return ConsoleContext.get().store().root().guidelines.values().stream()
            .filter(g -> projectId == null || projectId.equals(g.projectId()))
            .sorted(Comparator.comparing((LearnedGuideline g) -> statusRank(g.status()))
                .thenComparing(g -> g.slug() == null ? "" : g.slug()))
            .map(g -> {
                var dto = new GuidelineDto();
                dto.setId(g.id().toString());
                dto.setScope(g.scope() == null ? "" : g.scope().name());
                dto.setSlug(g.slug());
                dto.setTitle(g.title() == null ? "" : g.title());
                dto.setBody(g.markdownBody() == null ? "" : g.markdownBody());
                dto.setStatus(g.status() == null ? "" : g.status().name());
                dto.setSource(g.provenance() == null ? "" : g.provenance().source());
                // Where the rule came from, by name. A rule with no trail back to the paper
                // somebody wrote it in cannot be checked against its source, and that trail is the
                // one thing the requirements route had that guidelines did not (2026-08-31).
                dto.setDocument(g.provenance() == null || g.provenance().document() == null
                    ? "" : g.provenance().document());
                dto.setConfidence(g.confidence());
                dto.setCheckCommand(g.checkCommand() == null ? "" : g.checkCommand());
                dto.setCheckTimeoutSeconds(g.checkTimeoutSeconds());
                return dto;
            })
            .toList();
    }

    /** In use first, then awaiting a decision, then history — the order an operator scans. */
    private static int statusRank(GuidelineStatus status) {
        if (status == GuidelineStatus.ACTIVE) {
            return 0;
        }
        return status == GuidelineStatus.PROPOSED ? 1 : 2;
    }

    @Override
    public String setGuidelineCheck(String guidelineId, String command, int timeoutSeconds) {
        ConsoleContext.refuseIfWatching("change a rule's check");
        ConsoleContext.GuidelineControl control = ConsoleContext.get().guidelineControl();
        if (control == null) {
            return "error: this project's rules are not connected to the console, so a check "
                + "cannot be recorded here";
        }
        try {
            return control.setCheck(UUID.fromString(guidelineId), command, timeoutSeconds);
        } catch (IllegalArgumentException e) {
            return "error: no such guideline";
        }
    }

    @Override
    public String setGuidelineStatus(String guidelineId, String status) {
        ConsoleContext.refuseIfWatching("turn a rule on or off");
        ConsoleContext.GuidelineControl control = ConsoleContext.get().guidelineControl();
        if (control == null) {
            // Said plainly rather than returning "" and doing nothing: a button that reports
            // success and changes nothing is worse than one that says it is not connected.
            return "error: this project's rules are not connected to the console, so a rule "
                + "cannot be changed here";
        }
        String wanted = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        if (!wanted.equals("ACTIVE") && !wanted.equals("RETIRED")) {
            // PROPOSED is deliberately not offered. It means "a machine suggested this and nobody
            // has decided", which is a fact about how the rule arrived; an operator moving one
            // BACK to undecided would be recording something that never happened.
            return "error: a guideline is either in use (ACTIVE) or retired — nothing else";
        }
        try {
            return control.setStatus(UUID.fromString(guidelineId), wanted);
        } catch (IllegalArgumentException e) {
            return "error: no such guideline";
        }
    }

    @Override
    public void resolveDecision(String decisionId, String response) {
        ConsoleContext.refuseIfWatching("answer a question");
        // Through the same path the supervisor connection uses, so answering on screen also hands a
        // stopped build back to its engine (and raises a spending limit when told to extend).
        DecisionAnswers.Outcome outcome = DecisionAnswers.answerFromConsole(decisionId, response);
        if (!outcome.ok()) {
            throw new IllegalStateException(outcome.error());
        }
    }

    /**
     * Writes an answer on the decision row and nothing else: no restart, no limit raised. The part
     * of answering that {@link DecisionAnswers} builds on.
     */
    void recordAnswer(String decisionId, String response) {
        ConsoleContext.refuseIfWatching("answer a question");
        UUID id = UUID.fromString(decisionId);
        var store = ConsoleContext.get().store();
        try {
            // Awaited: the click must be durable before the UI reads the queue back.
            store.append(() -> {
                Decision decision = store.root().decisions.get(id);
                if (decision != null) {
                    store.root().decisions.put(id, new Decision(decision.id(), decision.runId(),
                        decision.kind(), decision.briefMarkdown(), DecisionState.RESOLVED,
                        response, decision.createdAt()));
                }
                return null;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted resolving decision", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to resolve decision", e.getCause());
        }
        // The Build stage's attention badge IS the count of unanswered decisions, so answering one
        // has to move it. Without this the operator works through all thirty-seven and the chip
        // still says thirty-seven until some unrelated backlog edit republishes readiness — which
        // is the same "the screen does not know what I just did" complaint in a new place.
        // Best-effort by contract: refresh() swallows everything, and a missed badge update must
        // never fail a decision that is already durable.
        ReadinessPublisher.refresh();
        // The BACKLOG too, because questions now travel on it: the pipeline board puts each one on the
        // card of the story it belongs to (UX v3 2.3), so answering one has to reach the board that is
        // showing it. Refreshing only readiness moved the badge and left the card still asking a
        // question that had already been answered — the same defect as answering thirty-seven of them
        // while the chip went on saying thirty-seven, one layer in.
        BacklogPublisher.publish(store, ConsoleContext.get().currentProjectId());
    }

    @Override
    public int resolveAllPendingDecisions(String response) {
        ConsoleContext.refuseIfWatching("answer questions");
        var store = ConsoleContext.get().store();
        AtomicInteger resolved = new AtomicInteger();
        try {
            // ONE append for the whole sweep, and awaited, for the same reason the single resolve
            // is awaited: the client's very next act is to read the queue back, and a list read
            // before the append lands would show all twenty-one still pending — which reads as a
            // button that did nothing, and invites a second press.
            store.append(() -> {
                // A copy, because the loop replaces entries in the map it is walking.
                for (Decision decision : new ArrayList<>(store.root().decisions.values())) {
                    if (decision == null || decision.state() != DecisionState.PENDING
                            || decision.kind() == DecisionKind.APPROVAL) {
                        continue;
                    }
                    store.root().decisions.put(decision.id(), new Decision(decision.id(),
                        decision.runId(), decision.kind(), decision.briefMarkdown(),
                        DecisionState.RESOLVED, response, decision.createdAt()));
                    resolved.incrementAndGet();
                }
                return null;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted resolving decisions", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to resolve decisions", e.getCause());
        }
        // The same reason resolveDecision refreshes, multiplied by however many were just cleared:
        // the Build badge IS the count of unanswered decisions, and clearing twenty-one of them
        // while the chip still says twenty-one is the exact "the screen does not know what I just
        // did" complaint the bulk resolve exists to answer. Best-effort — refresh() swallows
        // everything, and a missed badge update must never fail work that is already durable.
        ReadinessPublisher.refresh();
        // The BACKLOG too, because questions now travel on it: the pipeline board puts each one on the
        // card of the story it belongs to (UX v3 2.3), so answering one has to reach the board that is
        // showing it. Refreshing only readiness moved the badge and left the card still asking a
        // question that had already been answered — the same defect as answering thirty-seven of them
        // while the chip went on saying thirty-seven, one layer in.
        BacklogPublisher.publish(store, ConsoleContext.get().currentProjectId());
        return resolved.get();
    }

    // --- Running with nobody watching -----------------------------------------------------------

    @Override
    public String startAutonomousBuild() {
        ConsoleContext.refuseIfWatching("switch on unattended building");
        String result = AutonomousMode.start();
        log.info("startAutonomousBuild() -> {}", result.isEmpty() ? "on" : result);
        return result;
    }

    @Override
    public String stopAutonomousBuild() {
        // Named as the operator's own act, because that is what the diary will say happened.
        return AutonomousMode.stop("You stopped it. Anything already building carried on to its "
            + "end; nothing new was started and nothing further was decided for you.");
    }

    @Override
    public boolean autonomousRunning() {
        return AutonomousMode.isRunning();
    }

    @Override
    public String autonomousStatus() {
        return AutonomousMode.statusLine();
    }

    @Override
    public List<AutonomousDecision> autonomousDecisions() {
        ConsoleContext context = ConsoleContext.get();
        UUID projectId = context == null ? null : context.currentProjectId();
        return projectId == null ? List.of() : context.store().listAutonomousDecisions(projectId);
    }
}
