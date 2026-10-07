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

import com.swarmcoder.console.api.ControlService;
import com.swarmcoder.console.api.ControlService_Stub;
import com.swarmcoder.console.api.GuidelineDto;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.Card;
import com.zeroz4j.ui.component.CardTitle;
import com.zeroz4j.ui.component.TextField;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.HorizontalLayout;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;

import java.util.ArrayList;
import java.util.List;

/**
 * The project's rules, the button that decides whether each is in use, and the command that
 * proves one was obeyed.
 *
 * <p><b>Why the buttons exist</b> (author decision 2026-08-31). Every guideline SwarmCoder learns
 * is written PROPOSED, and a PROPOSED guideline never enters a prompt. This screen listed them
 * read-only, its only control was Refresh, and the only switch anywhere was a config flag deciding
 * what FUTURE extractions would be written as. So a project could hold two dozen correctly learned
 * lessons, none of which had ever reached a single worker, and the operator had nothing to click.
 *
 * <p>The wording is deliberately about EFFECT and not about status names: "Use this" and "Stop
 * using this", because what an operator wants to know is whether the rule is being followed, not
 * which of three constants it holds.
 *
 * <p><b>This screen is the only editing surface</b> (author decision 2026-09-02). Rules used to be
 * markdown files in the checkout and this screen wrote their first line; they are store objects
 * now, deleted with the project, and there is no file. That is also why the check command is
 * editable here — it used to be a line of front matter, and with the files gone it had nowhere
 * else to be written.
 */
final class GuidelinesView extends Card {

    private final ControlService control = new ControlService_Stub();
    private final ValueSignal<List<GuidelineDto>> guidelines = new ValueSignal<>(new ArrayList<>());
    /**
     * A failed load leaves the signal empty, and the list then renders "No guidelines yet." — a
     * statement about the project that happens to be false. This line says which one it is.
     */
    private final Div error = new Div();

    GuidelinesView() {
        HorizontalLayout header = new HorizontalLayout();
        header.addClassName("items-center");
        header.addClassName("gap-2");
        header.add(new CardTitle("Guidelines"));
        Button refresh = new Button("Refresh");
        refresh.addClassName("btn-xs");
        refresh.addClickListener(e -> refresh());
        header.add(refresh);
        add(header);

        Div hint = new Div();
        hint.addClassName("text-xs");
        hint.addClassName("opacity-60");
        hint.setText("These are the rules this project is held to. A rule marked in use is shown "
            + "to the architect, to whoever writes the tests, and to every worker, on every piece "
            + "of work. A proposed rule was learned from a previous build and nobody has decided "
            + "about it yet, so nothing is following it. Rules you stated yourself — from a "
            + "document you ticked as saying how the system must be built — are in use from the "
            + "moment you apply them. A rule can also carry a command that proves it: every "
            + "attempt at a piece of work is checked with it, and an attempt that fails it is "
            + "thrown away. The rules live in SwarmCoder, with the project; they are not files in "
            + "your code.");
        add(hint);

        error.addClassName("text-xs");
        error.addClassName("text-error");
        error.setVisible(false);
        add(error);

        Div list = new Div();
        add(list);
        Effect.create(() -> {
            list.getElement().setInnerHTML("");
            if (guidelines.get().isEmpty()) {
                Div empty = new Div();
                empty.addClassName("opacity-60");
                empty.setText("No guidelines yet.");
                list.add(empty);
            }
            for (GuidelineDto guideline : guidelines.get()) {
                list.add(row(guideline));
            }
        });

        refresh();
    }

    private Div row(GuidelineDto guideline) {
        Div row = new Div();
        row.addClassName("border-b");
        row.addClassName("border-base-300");
        row.addClassName("py-2");

        HorizontalLayout head = new HorizontalLayout();
        head.addClassName("gap-2");
        head.addClassName("items-center");
        Div status = new Div();
        status.addClassName("badge");
        status.addClassName(badgeClass(guideline.getStatus()));
        status.setText(guideline.getStatus());
        Div scope = new Div();
        scope.addClassName("badge");
        scope.addClassName("badge-ghost");
        scope.setText(guideline.getScope());
        Div slug = new Div();
        slug.addClassName("font-semibold");
        String name = guideline.getTitle() == null || guideline.getTitle().isBlank()
            ? guideline.getSlug() : guideline.getTitle();
        slug.setText(name);
        head.add(status, scope, slug);
        row.add(head);

        // Where it came from, in words rather than in a provenance constant. "stated" and
        // "extraction" mean nothing to somebody who has not read the code; who decided, and out of
        // which document, is the thing an operator needs in order to trust or retire a rule.
        Div origin = new Div();
        origin.addClassName("text-xs");
        origin.addClassName("opacity-60");
        origin.setText(originWords(guideline));
        row.add(origin);

        Div body = new Div();
        body.addClassName("text-sm");
        body.addClassName("mt-1");
        body.setText(guideline.getBody());
        row.add(body);

        // One button, and it always says what will change rather than what the rule currently is.
        // "Promote"/"Demote" would name a lifecycle the operator never asked to learn.
        HorizontalLayout actions = new HorizontalLayout();
        actions.addClassName("gap-2");
        actions.addClassName("mt-1");
        boolean inUse = "ACTIVE".equals(guideline.getStatus());
        Button toggle = new Button(inUse ? "Stop using this" : "Use this");
        toggle.addClassName("btn-xs");
        toggle.addClickListener(e -> setStatus(guideline, inUse ? "RETIRED" : "ACTIVE"));
        Div effect = new Div();
        effect.addClassName("text-xs");
        effect.addClassName("opacity-60");
        effect.setText(inUse
            ? "Every worker is told this rule."
            : "No worker is told this rule.");
        actions.add(toggle, effect);
        row.add(actions);

        // The command that proves the rule. Offered only for a rule a person decided: a command
        // on a rule a model proposed is never run (a model must not write what the orchestrator
        // executes), so a field for it would be a field that does nothing.
        String source = guideline.getSource() == null ? "" : guideline.getSource();
        if ("stated".equals(source) || "human".equals(source)) {
            HorizontalLayout check = new HorizontalLayout();
            check.addClassName("gap-2");
            check.addClassName("items-center");
            check.addClassName("mt-1");
            TextField command = new TextField("Command that proves this rule — exit 0 means obeyed");
            command.addClassName("input input-bordered input-xs w-96 font-mono text-xs");
            command.setValue(guideline.getCheckCommand() == null ? "" : guideline.getCheckCommand());
            Button save = new Button("Save check");
            save.addClassName("btn-xs");
            save.addClickListener(e -> setCheck(guideline, command.getValue()));
            Div checked = new Div();
            checked.addClassName("text-xs");
            checked.addClassName("opacity-60");
            boolean hasCheck = guideline.getCheckCommand() != null
                && !guideline.getCheckCommand().isBlank();
            checked.setText(hasCheck
                ? "Checked: work that fails this command is thrown away."
                : "Advice only: nothing checks it. Add a command to make it a hard rule.");
            check.add(command, save, checked);
            row.add(check);
        }
        return row;
    }

    /**
     * Applies the change, then re-reads the list from the server.
     *
     * <p>Re-read rather than patched in place: what the rule ends up saying is the server's
     * business and not this screen's guess at it. A refusal is shown in the same line a failed
     * load uses — the operator must never be left looking at a row that did not change with
     * nothing saying why.
     */
    private void setStatus(GuidelineDto guideline, String status) {
        apply(guideline, () -> control.setGuidelineStatus(guideline.getId(), status));
    }

    private void setCheck(GuidelineDto guideline, String command) {
        apply(guideline, () -> control.setGuidelineCheck(guideline.getId(), command, 0));
    }

    private void apply(GuidelineDto guideline, java.util.function.Supplier<String> change) {
        try {
            String result = change.get();
            if (result != null && result.startsWith("error:")) {
                error.setText(result.substring("error:".length()).strip());
                error.setVisible(true);
                return;
            }
            error.setVisible(false);
        } catch (Exception ex) {
            error.setText("Could not change " + guideline.getSlug() + ": " + ex.getMessage());
            error.setVisible(true);
            ClientLog.error("GuidelinesView", "changing a guideline failed: " + ex);
            return;
        }
        refresh();
    }

    /** Who decided this rule and where it came from, said plainly. */
    private static String originWords(GuidelineDto guideline) {
        String document = guideline.getDocument();
        String source = guideline.getSource() == null ? "" : guideline.getSource();
        String where = document == null || document.isBlank() ? "" : ", from " + document;
        String who = switch (source) {
            case "stated" -> "You stated this";
            case "extraction" -> "SwarmCoder learned this from an earlier build";
            case "human" -> "Written by hand";
            default -> "Origin not recorded";
        };
        return who + where;
    }

    private static String badgeClass(String status) {
        if ("ACTIVE".equals(status)) {
            return "badge-success";
        }
        if ("PROPOSED".equals(status)) {
            return "badge-warning";
        }
        return "badge-ghost";
    }

    private void refresh() {
        
            try {
                guidelines.set(control.guidelines());
                error.setVisible(false);
            } catch (Exception ex) {
                // System.out in the compiled client reaches only a devtools console, so this used
                // to leave the operator reading "No guidelines yet." with nothing to contradict it.
                error.setText("Could not load guidelines: " + ex.getMessage());
                error.setVisible(true);
                ClientLog.error("GuidelinesView", "guidelines failed to load — the view claims "
                    + "there are none rather than that it could not read them: " + ex);
            }
    }
}
