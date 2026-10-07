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
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;

/**
 * One value the operator needs to get OUT of the Console — an id, a folder, a hash — shown as text
 * and copied by a button that really copies.
 *
 * <h2>Why this is not {@code PropertyGrid.row(String, String)}</h2>
 *
 * <p>The component library's own two-column grid draws exactly this, and its copy button does
 * nothing. Its listener is registered through {@code addEventListener("click", threaded(...))},
 * and {@code threaded} is {@code new Thread(...).start()} — so the copy runs on a later scheduler
 * tick, after the browser's user gesture has expired, and {@code document.execCommand('copy')} is
 * refused. Nothing is written and nothing is said. See {@link Clipboard} for the whole mechanism.
 *
 * <p>Rather than a private copy of the grid, this is a value CELL: the surrounding
 * {@code PropertyGrid} is still the framework's, placed through its
 * {@code row(String, Component)} overload, so the two-column layout stays shared and only the
 * broken half is replaced.
 *
 * <h2>Two ways to get the value, always</h2>
 *
 * <p>The button is the fast way. The text itself carries {@code select-all}, so one click on the
 * value selects the whole of it however long it is — which is the way that cannot fail, and the
 * way the operator is pointed at by name when the browser refuses the clipboard.
 */
final class CopyValue extends Div {

    /** Nothing recorded — an em dash, and no copy button, because there is nothing to copy. */
    private static final String NOTHING = "—";

    private final Span value = new Span();
    private final Span notice = new Span();

    /**
     * @param label what this value is, used for the button's spoken name and its test handle
     * @param text  the value; null or empty draws an em dash and no button
     */
    CopyValue(String label, String text) {
        String slug = slug(label);
        boolean present = text != null && !text.isEmpty();

        addClassName("flex items-baseline gap-1 min-w-0 flex-wrap");

        value.setText(present ? text : NOTHING);
        // select-all: one click selects the whole value, which is the fallback that cannot fail.
        // break-all so a long path wraps inside the dialog instead of widening it.
        value.addClassName("text-xs font-mono break-all select-all cursor-text");
        value.getElement().setAttribute("data-testid", "copy-value-" + slug);
        value.getElement().setAttribute("title", "Click to select the whole value");
        add(value);

        if (present) {
            Button copy = new Button(Icon.of("copy", "w-3 h-3"), "Copy " + label);
            copy.setClassName("btn btn-ghost btn-xs btn-circle shrink-0 opacity-50 "
                + "hover:opacity-100");
            copy.getElement().setAttribute("type", "button");
            copy.getElement().setAttribute("data-testid", "copy-" + slug);
            copy.getElement().setAttribute("title", "Copy " + label);
            // Straight onto the element, unwrapped: see Clipboard.
            Clipboard.onCopyClick(copy.getElement(), () -> text, this::say);
            add(copy);
        }

        notice.addClassName("text-[11px] shrink-0");
        notice.setVisible(false);
        add(notice);
    }

    /**
     * Says what happened, every time — the whole point of the exercise.
     *
     * <p>On a refusal the value is also SELECTED, so "press Ctrl+C" is an instruction the operator
     * can follow immediately rather than an apology.
     */
    private void say(boolean copied) {
        if (copied) {
            notice.setText("Copied");
            notice.setClassName("text-[11px] shrink-0 text-success");
        } else {
            Clipboard.select(value.getElement());
            notice.setText("Could not reach the clipboard — it is selected, press Ctrl+C");
            notice.setClassName("text-[11px] shrink-0 text-warning");
        }
        notice.setVisible(true);
    }

    /** A stable test handle and css-safe suffix from a human label ("primary path" → "primary-path"). */
    private static String slug(String label) {
        if (label == null || label.isEmpty()) {
            return "value";
        }
        StringBuilder out = new StringBuilder(label.length());
        for (char c : label.toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                out.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                out.append((char) (c - 'A' + 'a'));
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
        }
        return out.length() == 0 ? "value" : out.toString();
    }
}
