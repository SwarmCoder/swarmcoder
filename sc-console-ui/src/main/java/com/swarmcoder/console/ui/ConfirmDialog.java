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

import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.Dialog;
import com.zeroz4j.ui.layout.Div;

/**
 * One shape for "this will cost you something — do you still want to?".
 *
 * <p>Three actions in the requirements screens are irreversible-looking and were silent: rewording a
 * requirement a story is already building, taking a requirement out of scope, and restoring an old
 * revision. Each now asks first, and each shows a sentence the SERVER computed about that exact
 * requirement or revision — never a generic "are you sure", which teaches people to click through
 * without reading.
 *
 * <p>Since ZeroZ Stack 0.8.0 a dialog closes on Escape and on a click outside on its own, so there
 * is no dismiss path to hand-build here. The explicit second button stays because a keyboard
 * shortcut is not an affordance.
 */
final class ConfirmDialog {

    private ConfirmDialog() {
    }

    /**
     * Opens the question inside {@code host}'s DOM tree and runs {@code action} only if the operator
     * says yes.
     *
     * @param host        the component the dialog is mounted in, and taken out of again when it
     *                    closes — a dialog left in the document keeps every control it carries,
     *                    including buttons that can never be clicked but still answer to a selector
     *                    looking for the next dialog's
     * @param heading     the question, short
     * @param sentence    what it will cost, in the words the server computed
     * @param confirmLabel what the operator is about to do, said as the act itself, never "OK"
     * @param testId      marks the dialog's body; the confirming button is {@code testId + "-confirm"}
     */
    static void ask(Div host, String heading, String sentence, String confirmLabel, String testId,
                    Runnable action) {
        Dialog dialog = new Dialog();
        dialog.setWidth("34rem");
        Div body = new Div();
        body.addClassName("flex flex-col gap-2");
        body.getElement().setAttribute("data-testid", testId);
        Div title = new Div(heading);
        title.addClassName("text-base font-semibold");
        body.add(title);
        Div what = new Div(sentence);
        what.addClassName("text-sm leading-relaxed text-base-content/80");
        body.add(what);
        dialog.add(body);

        Button no = new Button("Leave it as it is");
        no.addClassName("btn-sm btn-ghost");
        no.addClickListener(e -> dialog.close());
        Button go = new Button(confirmLabel);
        go.addClassName("btn-sm btn-primary");
        go.getElement().setAttribute("data-testid", testId + "-confirm");
        go.addClickListener(e -> {
            dialog.close();
            action.run();
        });
        dialog.addAction(no);
        dialog.addAction(go);

        host.add(dialog);
        dialog.addCloseListener(e -> host.remove(dialog));
        dialog.open();
    }
}
