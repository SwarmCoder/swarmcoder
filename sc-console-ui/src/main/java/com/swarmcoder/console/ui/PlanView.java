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
package com.swarmcoder.console.ui;

import com.swarmcoder.console.api.GraphRequirementDto;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.console.api.GraphTaskDto;
import com.swarmcoder.console.api.RunGraphDto;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.ui.component.EmptyState;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.StatusDot;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import com.zeroz4j.signals.Effect;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The requirements + task-list surface for a run (the "requirements document" made visible):
 * every requirement as a first-class row with its priority and which tasks cover it, and every
 * task with its live state, the requirements it satisfies, and its dependencies. Reads the same
 * {@code run-graph} push signal the graph does, so it advances in real time as tasks are worked
 * through — no refresh.
 */
final class PlanView extends Div implements Disposable {

    private final String runId;
    private final Div body = new Div();
    /**
     * The binding to the run-graph signal, which is process-wide. Released on {@link #dispose()},
     * so a closed run tab stops redrawing a subtree that has left the document.
     */
    private final Disposable redraw;

    PlanView(String runId) {
        this.runId = runId;
        addClassName("flex flex-col h-full min-h-0 overflow-y-auto");
        body.addClassName("flex flex-col gap-6 p-5 max-w-4xl w-full mx-auto");
        add(body);

        redraw = Effect.create(() -> {
            RunGraphDto graph = GraphStore.graph(runId).get();
            body.removeAll();
            if (graph == null
                    || (graph.getRequirements().isEmpty() && graph.getTasks().isEmpty())) {
                body.add(new EmptyState("file", "No plan yet",
                    "Once the architect drafts a design and decomposes it into tasks, the "
                    + "requirements and the task list appear here — and tick over as the swarm "
                    + "works through them."));
                return;
            }
            body.add(requirementsSection(graph));
            body.add(tasksSection(graph));
        });
    }

    @Override
    public void dispose() {
        redraw.dispose();
    }

    // --- requirements ----------------------------------------------------------------------------

    private Div requirementsSection(RunGraphDto graph) {
        List<GraphRequirementDto> requirements = graph.getRequirements();
        Map<String, String> taskTitles = taskTitles(graph);

        Div section = new Div();
        section.addClassName("flex flex-col gap-2.5");

        long covered = requirements.stream()
            .filter(r -> !r.getCoveredByCsv().isEmpty()).count();
        section.add(sectionHeader("list", "Requirements", requirements.size(),
            requirements.isEmpty() ? "" : covered + " of " + requirements.size() + " covered"));

        if (requirements.isEmpty()) {
            section.add(hint("The design carries no requirements."));
            return section;
        }
        for (GraphRequirementDto requirement : requirements) {
            section.add(requirementCard(requirement, taskTitles));
        }
        return section;
    }

    private Div requirementCard(GraphRequirementDto requirement, Map<String, String> taskTitles) {
        boolean covered = !requirement.getCoveredByCsv().isEmpty();

        Div card = new Div();
        card.addClassName("rounded-xl border p-3.5 flex flex-col gap-2 "
            + (covered ? "border-base-300 bg-base-200/40" : "border-warning/40 bg-warning/5"));

        Div top = new Div();
        top.addClassName("flex items-center gap-2");
        Span handle = new Span(requirement.getHandle());
        handle.addClassName("font-mono text-xs font-bold bg-base-300/70 rounded px-1.5 py-0.5");
        top.getElement().appendChild(handle.getElement());
        if (!requirement.getPriority().isEmpty()) {
            Span priority = new Span(requirement.getPriority());
            priority.addClassName("badge badge-sm " + priorityBadge(requirement.getPriority()));
            top.getElement().appendChild(priority.getElement());
        }
        Div spacer = new Div();
        spacer.addClassName("flex-1");
        top.add(spacer);
        String[] covers = covered ? requirement.getCoveredByCsv().split(",") : new String[0];
        Span coverage = new Span(covered
            ? "✓ " + covers.length + (covers.length == 1 ? " task" : " tasks")
            : "⚠ not covered");
        coverage.addClassName("text-xs font-semibold "
            + (covered ? "text-success" : "text-warning"));
        top.getElement().appendChild(coverage.getElement());
        card.add(top);

        Span text = new Span(requirement.getText());
        text.addClassName("text-sm leading-snug");
        card.getElement().appendChild(text.getElement());

        if (covered) {
            Div chips = new Div();
            chips.addClassName("flex flex-wrap gap-1.5 pt-0.5");
            for (String taskId : covers) {
                chips.add(taskChip(taskTitles.getOrDefault(taskId, shortId(taskId))));
            }
            card.add(chips);
        }
        return card;
    }

    // --- tasks -----------------------------------------------------------------------------------

    private Div tasksSection(RunGraphDto graph) {
        List<GraphTaskDto> tasks = graph.getTasks();
        Map<String, String> handles = requirementHandles(graph);

        Div section = new Div();
        section.addClassName("flex flex-col gap-2.5");

        long done = tasks.stream().filter(t -> "DONE".equals(t.getState())).count();
        section.add(sectionHeader("check", "Tasks", tasks.size(),
            tasks.isEmpty() ? "" : done + " of " + tasks.size() + " done"));

        if (tasks.isEmpty()) {
            section.add(hint("No tasks planned yet."));
            return section;
        }
        for (GraphTaskDto task : tasks) {
            section.add(taskRow(task, handles));
        }
        return section;
    }

    private Div taskRow(GraphTaskDto task, Map<String, String> handles) {
        Div row = new Div();
        row.addClassName("rounded-xl border border-base-300 bg-base-200/40 p-3.5 "
            + "flex flex-col gap-2");

        Div top = new Div();
        top.addClassName("flex items-center gap-2");
        // Colour from the state, hover text from the wording — a dot hovering as DISPATCHED
        // is the machine describing itself just as much as a badge reading it (UX v3 §5 r3).
        top.add(new StatusDot(task.getState(), TaskState.labelOf(task.getState())));
        Span title = new Span(task.getTitle());
        title.addClassName("font-semibold text-sm truncate");
        top.getElement().appendChild(title.getElement());
        Div spacer = new Div();
        spacer.addClassName("flex-1");
        top.add(spacer);
        // DISPATCHED, VERIFYING, JUDGING, INTEGRATED: ten Java constants were rendered here
        // verbatim. TaskState.label() is the one wording of each (UX v3 §5 rules 2 and 3).
        Span state = new Span(task.getState().isEmpty()
            ? "—" : TaskState.labelOf(task.getState()));
        state.addClassName("badge badge-sm " + stateBadge(task.getState()));
        top.getElement().appendChild(state.getElement());
        row.add(top);

        Div meta = new Div();
        meta.addClassName("flex items-center flex-wrap gap-x-3 gap-y-1 text-[11px] "
            + "text-base-content/50 font-mono");
        if (!task.getWriteSetCsv().isEmpty()) {
            meta.add(metaItem("file", task.getWriteSetCsv()));
        }
        int deps = task.getDependsOnCsv().isEmpty() ? 0 : task.getDependsOnCsv().split(",").length;
        if (deps > 0) {
            meta.add(metaItem("graph", deps + (deps == 1 ? " dep" : " deps")));
        }
        meta.add(metaItem("check", task.getCriteria()
            + (task.getCriteria() == 1 ? " check" : " checks")));
        row.add(meta);

        String[] reqIds = task.getRequirementIdsCsv().isEmpty()
            ? new String[0] : task.getRequirementIdsCsv().split(",");
        if (reqIds.length > 0) {
            Div chips = new Div();
            chips.addClassName("flex flex-wrap gap-1.5");
            for (String reqId : reqIds) {
                Span chip = new Span(handles.getOrDefault(reqId, "req"));
                chip.addClassName("font-mono text-[10px] font-bold text-primary "
                    + "bg-primary/10 rounded px-1.5 py-0.5");
                chips.getElement().appendChild(chip.getElement());
            }
            row.add(chips);
        }
        return row;
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static Div sectionHeader(String icon, String label, int count, String note) {
        Div header = new Div();
        header.addClassName("flex items-center gap-2");
        header.add(Icon.of(icon, "w-4 h-4 text-primary"));
        Span title = new Span(label);
        title.addClassName("text-base font-bold");
        header.getElement().appendChild(title.getElement());
        Span countBadge = new Span(String.valueOf(count));
        countBadge.addClassName("badge badge-sm badge-neutral");
        header.getElement().appendChild(countBadge.getElement());
        Div spacer = new Div();
        spacer.addClassName("flex-1");
        header.add(spacer);
        if (!note.isEmpty()) {
            Span noteSpan = new Span(note);
            noteSpan.addClassName("text-xs text-base-content/50 font-mono");
            header.getElement().appendChild(noteSpan.getElement());
        }
        return header;
    }

    private static Div metaItem(String icon, String text) {
        Div item = new Div();
        item.addClassName("flex items-center gap-1 truncate");
        item.add(Icon.of(icon, "w-3 h-3 opacity-50"));
        Span label = new Span(text);
        label.addClassName("truncate max-w-[16rem]");
        item.getElement().appendChild(label.getElement());
        return item;
    }

    private static Span taskChip(String title) {
        Span chip = new Span(title);
        chip.addClassName("text-[11px] bg-base-300/60 rounded px-1.5 py-0.5 truncate max-w-[14rem]");
        return chip;
    }

    private static Div hint(String text) {
        Div div = new Div(text);
        div.addClassName("text-sm text-base-content/50 px-1");
        return div;
    }

    private static Map<String, String> taskTitles(RunGraphDto graph) {
        Map<String, String> titles = new HashMap<>();
        for (GraphTaskDto task : graph.getTasks()) {
            titles.put(task.getTaskId(), task.getTitle());
        }
        return titles;
    }

    private static Map<String, String> requirementHandles(RunGraphDto graph) {
        Map<String, String> handles = new HashMap<>();
        for (GraphRequirementDto requirement : graph.getRequirements()) {
            handles.put(requirement.getRequirementId(), requirement.getHandle());
        }
        return handles;
    }

    private static String priorityBadge(String priority) {
        return switch (priority == null ? "" : priority) {
            case "CRITICAL" -> "badge-error";
            case "HIGH" -> "badge-warning";
            case "MEDIUM" -> "badge-info";
            default -> "badge-ghost";
        };
    }

    private static String stateBadge(String state) {
        return switch (state == null ? "" : state) {
            case "DONE", "SELECTED", "INTEGRATED" -> "badge-success";
            case "BLOCKED", "CANCELLED" -> "badge-error";
            case "DISPATCHED", "VERIFYING", "JUDGING" -> "badge-info";
            default -> "badge-ghost";
        };
    }

    private static String shortId(String id) {
        return id == null || id.length() < 8 ? String.valueOf(id) : id.substring(0, 8);
    }
}

