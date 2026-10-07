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

import com.swarmcoder.console.api.Backlog;
import com.swarmcoder.console.api.BacklogSignals;
import com.swarmcoder.console.api.BrdSignals;
import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.Brd;
import com.swarmcoder.domain.BrdRequirement;
import com.swarmcoder.domain.RequirementStatus;
import com.swarmcoder.domain.Story;
import com.zeroz4j.api.Disposable;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The agreed scope, beside the plan, read-only.
 *
 * <p>You plan <em>against</em> requirements; editing them is the previous stage's job. That is not a
 * technical restriction — the BRD editor is one click away on the stage bar — it is what makes the
 * Plan stage a stage: while you are deciding what to build next, a requirement is a fixed thing you
 * are answering to, and a text box invites you to quietly move the goalposts to fit the plan.
 *
 * <p>It answers the one question a story cannot answer about itself: which criteria has anybody
 * undertaken to deliver? Each criterion is marked <em>planned</em> or not, read from the same
 * {@link BacklogSignals#CURRENT} the backlog binds to, so the two surfaces can never disagree.
 *
 * <p>Bound to two process-wide shared signals; callers MUST {@link #dispose()} it.
 */
final class RequirementsReference extends Div implements Disposable {

    private final List<Disposable> disposables = new ArrayList<>();
    private final Div body = new Div();
    /** How the owning stage collapses this pane, or null when it cannot be collapsed. */
    private final Runnable onHide;

    RequirementsReference(Runnable onHide) {
        this.onHide = onHide;
        addClassName("flex flex-col h-full min-h-0 bg-base-200/30");
        add(header());
        body.addClassName("flex-1 min-h-0 overflow-y-auto px-2 py-1");
        add(body);
        disposables.add(Effect.create(this::render));
    }

    @Override
    public void dispose() {
        for (Disposable d : disposables) {
            d.dispose();
        }
        disposables.clear();
    }

    private Div header() {
        Div bar = new Div();
        bar.addClassName("flex items-center gap-2 px-3 py-2 border-b border-base-300 shrink-0");
        Span title = new Span("Requirements");
        title.addClassName("text-sm font-semibold");
        bar.getElement().appendChild(title.getElement());
        Span note = new Span("reference");
        note.addClassName("text-[10px] uppercase tracking-wider text-base-content/40");
        bar.getElement().appendChild(note.getElement());
        Div spacer = new Div();
        spacer.addClassName("flex-1");
        bar.add(spacer);
        // Not a disabled editor — the way to edit is named and one click away.
        Div edit = new Div("Edit in Requirements");
        edit.addClassName("text-[11px] px-1.5 py-0.5 rounded border border-base-300 cursor-pointer "
            + "text-primary hover:bg-base-300/60");
        edit.addDomEventListener("click", e -> Nav.openStage.accept(Stage.REQUIREMENTS));
        bar.add(edit);
        if (onHide != null) {
            Div hide = new Div();
            hide.addClassName("cursor-pointer text-base-content/40 hover:text-error");
            hide.add(Icon.of("x", "w-3.5 h-3.5"));
            hide.getElement().setAttribute("title", "Hide the requirements reference");
            hide.addDomEventListener("click", e -> onHide.run());
            bar.add(hide);
        }
        return bar;
    }

    /** No RMI here: everything is already on the two signals, and this runs inside an Effect. */
    private void render() {
        Brd brd = BrdSignals.CURRENT.get();
        Set<UUID> planned = plannedCriteria(BacklogSignals.CURRENT.get());
        body.getElement().setInnerHTML("");

        List<BrdRequirement> requirements =
            brd == null || brd.requirements() == null ? List.of() : brd.requirements();
        if (requirements.isEmpty()) {
            Div empty = new Div();
            empty.addClassName("px-2 py-3 text-[11px] text-base-content/40 leading-relaxed");
            empty.setText("No requirements yet. There is nothing to plan against until the "
                + "Requirements stage has some — that is the step before this one.");
            body.add(empty);
            return;
        }
        for (BrdRequirement requirement : requirements) {
            body.add(row(requirement, planned));
        }
    }

    /**
     * How many accepted criteria no story has undertaken to deliver.
     *
     * <p>Lives here rather than in {@code PlanStage} because it is the same reading of the same two
     * signals that {@link #render()} does, and two places computing "planned" from different rules
     * is how a count and the list under it come to disagree. The stage puts this number on the
     * collapsed edge, where it is the reason to open the pane at all.
     */
    static int unclaimedCriteria(Brd brd, Backlog backlog) {
        if (brd == null || brd.requirements() == null) {
            return 0;
        }
        Set<UUID> planned = plannedCriteria(backlog);
        int unclaimed = 0;
        for (BrdRequirement requirement : brd.requirements()) {
            if (requirement.criteria() == null) {
                continue;
            }
            for (AcceptanceCriterion criterion : requirement.criteria()) {
                if (criterion.id() != null && !planned.contains(criterion.id())) {
                    unclaimed++;
                }
            }
        }
        return unclaimed;
    }

    /** Every criterion id some non-cancelled story has undertaken to deliver. */
    private static Set<UUID> plannedCriteria(Backlog backlog) {
        Set<UUID> planned = new HashSet<>();
        if (backlog == null) {
            return planned;
        }
        for (Story story : backlog.stories()) {
            if (story.state() != com.swarmcoder.domain.StoryState.CANCELLED) {
                planned.addAll(story.criterionIds());
            }
        }
        return planned;
    }

    private Div row(BrdRequirement requirement, Set<UUID> planned) {
        Div card = new Div();
        card.addClassName("rounded border border-base-300 bg-base-100/60 px-2 py-1.5 mb-1.5");

        Div top = new Div();
        top.addClassName("flex items-center gap-1.5");
        Span handle = new Span(requirement.handle() == null ? "" : requirement.handle());
        handle.addClassName("text-[10px] font-mono text-base-content/40");
        top.getElement().appendChild(handle.getElement());
        Span title = new Span(requirement.title() == null ? "" : requirement.title());
        title.addClassName("text-xs font-medium flex-1 truncate");
        top.getElement().appendChild(title.getElement());
        // The words, not the constant: this chip used to read DRAFT / ACTIVE / IMPLEMENTED
        // (UX v3 §5 rule 3). RequirementStatus.label() is the single wording of each.
        Span status = new Span(requirement.status() == null
            ? "" : requirement.status().label());
        status.addClassName("text-[9px] uppercase tracking-wide px-1 rounded "
            + statusColor(requirement.status()));
        top.getElement().appendChild(status.getElement());
        card.add(top);

        List<AcceptanceCriterion> criteria =
            requirement.criteria() == null ? List.of() : requirement.criteria();
        if (criteria.isEmpty()) {
            Span none = new Span("no checks — nothing to select into a story");
            none.addClassName("text-[10px] text-base-content/35");
            card.getElement().appendChild(none.getElement());
            return card;
        }
        for (AcceptanceCriterion criterion : criteria) {
            Div line = new Div();
            line.addClassName("flex items-start gap-1.5 pl-1 pt-0.5");
            boolean isPlanned = criterion.id() != null && planned.contains(criterion.id());
            Span dot = new Span(isPlanned ? "●" : "○");
            dot.addClassName("text-[8px] mt-1 shrink-0 "
                + (isPlanned ? "text-success" : "text-base-content/30"));
            line.getElement().appendChild(dot.getElement());
            Span text = new Span(criterion.text() == null ? "" : criterion.text());
            text.addClassName("text-[11px] leading-snug "
                + (isPlanned ? "text-base-content/70" : "text-base-content/50"));
            line.getElement().appendChild(text.getElement());
            line.getElement().setAttribute("title", isPlanned
                ? "A story has undertaken to deliver this."
                : "Nothing in the plan delivers this yet.");
            card.add(line);
        }
        return card;
    }

    private static String statusColor(RequirementStatus status) {
        if (status == null) {
            return "bg-base-300 text-base-content/50";
        }
        return switch (status) {
            case DRAFT -> "bg-warning/20 text-warning";
            case ACTIVE -> "bg-success/15 text-success";
            case IMPLEMENTED -> "bg-primary/15 text-primary";
            default -> "bg-base-300 text-base-content/50";
        };
    }
}
