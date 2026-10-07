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

import com.zeroz4j.ui.component.Checkbox;
import com.zeroz4j.ui.layout.Div;

import java.util.function.Consumer;

/**
 * The one place the Console asks whether a document says how the system must be BUILT.
 *
 * <p>Two screens collect documents — {@link IntakeWizard}'s document step and
 * {@link OnboardingWizard}'s "Add what you have written down" — and both have to ask this, because
 * <b>whichever one an operator is on is the last moment before an analyst reads the file</b>. The
 * question has to be asked in the same words in both places or it is two different questions, so
 * the tick, its wording and its explanation live here and neither screen owns a copy.
 *
 * <p>This is deliberately NOT part of {@link DocumentUpload}. That component asks for bytes: it is
 * a drop box with a progress bar, it knows nothing about a flow, a document id or an analysis, and
 * it is also mounted in the chat composer where there is no document row to put a tick in. This
 * tick is a fact about one already-attached document, set against one flow, and it belongs in the
 * row that names that document.
 *
 * <p><b>Why the operator answers it and not the model.</b> A model deciding which documents are
 * about technology would be a guess about technology made by the very thing whose guesses about
 * technology are the problem this exists to stop.
 */
final class DocumentKind {

    /** The question, in the operator's words. Identical on every screen that asks it. */
    static final String LABEL = "This says how the system must be BUILT, not what it must do";

    /** What ticking it means, on hover. */
    static final String EXPLANATION =
        "Tick this for a document about the technology: the language, the frameworks, how data "
            + "is stored, what must not be used. Rules like these are never finished and nobody "
            + "is given them as a job — every piece of work has to follow them.";

    /**
     * Said once, quietly, on a screen holding documents where none of them is the technical one.
     *
     * <p>It is a caption and nothing more: no colour, no button, and it does not stop the analysis
     * starting. A project really can be business-only, and a warning that fires on the ordinary
     * case is a warning people learn to click past. But this screen is the last moment the fact can
     * be stated at all, and a project whose technology was never written down anywhere is the one
     * that had Spring Boot tests written into a project that has no Spring in it.
     */
    static final String NONE_TICKED =
        "None of these is ticked. If you have written down what this system must be made of — the "
            + "language, the frameworks, what must not be used — tick it above, and every piece of "
            + "work will have to follow it. If you have not written that down, carry on: "
            + "SwarmCoder will choose for itself.";

    private DocumentKind() {}

    /**
     * The tick and its wording, as one row ready to be added to a document's line.
     *
     * <p>{@code testId} names the checkbox itself so a browser test can reach it without selecting
     * on the wording. {@code onChange} is handed the new value and runs on the click handler's
     * thread, so it may call the server.
     */
    static Div tick(boolean technical, String testId, Consumer<Boolean> onChange) {
        Div row = new Div();
        row.addClassName("flex items-center gap-2");
        Checkbox box = new Checkbox();
        box.addClassName("checkbox-sm");
        box.setValue(technical);
        box.getElement().setAttribute("data-testid", testId);
        box.addValueChangeListener(e -> onChange.accept(Boolean.TRUE.equals(box.getValue())));
        Div label = new Div(LABEL);
        label.addClassName("text-xs text-base-content/70");
        label.getElement().setAttribute("title", EXPLANATION);
        row.add(box, label);
        return row;
    }
}
