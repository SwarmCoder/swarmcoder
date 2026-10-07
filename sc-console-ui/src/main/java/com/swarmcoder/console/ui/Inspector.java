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

import com.zeroz4j.ui.component.Component;
import com.zeroz4j.signals.ValueSignal;

import org.teavm.jso.browser.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * The drill-down (design §2 R4): a stack of levels with breadcrumbs. {@code open} resets to one
 * level, {@code push} goes deeper, breadcrumb clicks pop back. MainView owns the rendering; every
 * view just calls these statics.
 *
 * <h2>Where it is drawn, and why that is not always the same place</h2>
 *
 * <p>The drill-down has two homes, and {@link #overDialog} decides which. Opened from an ordinary
 * screen it is the right-hand panel it has always been. Opened from inside a dialog — a candidate
 * clicked in the run graph, which BuildStage and PipelineBoard both show as a dialog — it is drawn
 * as a second dialog over the first.
 *
 * <p>That is not a preference. Since ZeroZ Stack 0.8.0 a dialog is a real one: opening it hands the
 * element to the browser, which draws it in the <b>top layer</b> and makes everything outside it
 * inert. The top layer is above every stacking number there is, so a panel in the ordinary page
 * cannot be raised over a dialog by any means — the panel appeared beside the dialog looking
 * perfectly usable while every click on it landed on the dialog's backdrop, and nothing but another
 * dialog can be put in front. A second dialog over the first is also already this console's idiom
 * for going deeper from one: both wizards do it, and so does the full-payload viewer in
 * {@link TranscriptPane}.
 *
 * <p>The levels, the breadcrumbs and the calling code are the same either way. Only the container
 * changes.
 */
final class Inspector {

    record Level(String title, Component content) {}

    static final ValueSignal<List<Level>> stack = new ValueSignal<>(new ArrayList<>());
    static final ValueSignal<Boolean> visible = new ValueSignal<>(false);
    /**
     * True while this drill-down belongs over a dialog rather than in the shell's right-hand rail.
     *
     * <p>Sampled once, when the drill-down is opened, rather than on every render: by the time the
     * levels are drawn a dialog is certainly open, because the drill-down is one.
     */
    static final ValueSignal<Boolean> overDialog = new ValueSignal<>(false);

    private Inspector() {}

    static void open(String title, Component content) {
        overDialog.set(aDialogIsOpen());
        List<Level> next = new ArrayList<>();
        next.add(new Level(title, content));
        stack.set(next);
        visible.set(true);
    }

    static void push(String title, Component content) {
        List<Level> next = new ArrayList<>(stack.get());
        next.add(new Level(title, content));
        stack.set(next);
        visible.set(true);
    }

    static void popTo(int depth) {
        List<Level> current = stack.get();
        if (depth >= 0 && depth < current.size()) {
            stack.set(new ArrayList<>(current.subList(0, depth + 1)));
        }
    }

    static void close() {
        visible.set(false);
        overDialog.set(false);
    }

    /**
     * Whether anything is in the browser's top layer right now.
     *
     * <p>{@code showModal()} is what puts the {@code open} attribute on a native dialog, and it is
     * also exactly what makes the rest of the page inert — so this one selector answers both "is a
     * dialog showing" and "would a panel drawn in the page be unclickable". A dialog opened with
     * {@code setModal(false)}, which takes nothing over, is correctly not counted.
     */
    private static boolean aDialogIsOpen() {
        return Window.current().getDocument().querySelector("dialog[open]") != null;
    }
}
