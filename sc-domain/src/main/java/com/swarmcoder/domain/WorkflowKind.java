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
package com.swarmcoder.domain;

import java.util.Arrays;
import java.util.List;

/**
 * The kinds of run the product offers.
 *
 * <p>Four of the six used to have a "workflow" class of their own that renamed the run's state a
 * few times and then marked it DELIVERED — no design, no plan, no acceptance test, no worker, no
 * build. All four were on the chat's command menu and in the user manual. What replaced them:
 *
 * <ul>
 *   <li><b>BUGFIX</b> and <b>REFACTOR</b> are real work of the same shape as a feature, differing
 *       only in what the architect and the test author are told, so they now run the ONE delivery
 *       path ({@code GreenfieldWorkflow}) with a kind-specific brief. A bugfix's acceptance test is
 *       the reproduction — the red-check already requires it to fail before any worker starts. A
 *       refactor's brief forbids behaviour change, and the existing tests already run on every
 *       candidate.</li>
 *   <li><b>DOCS</b> and <b>ANALYSIS</b> are not builds. Nothing they produce can be expressed as a
 *       check that a test proves, so the red-check, the verification pipeline and the merge audit —
 *       everything that makes a claim of delivery mean anything here — have nothing to work with.
 *       They are {@linkplain #isStartable() unstartable}: off the menu, out of the manual, refused
 *       by intake, and any left in a store from an older build are ABORTED rather than advanced.</li>
 * </ul>
 *
 * <p>The two constants stay in the enum because runs persisted by older builds carry them; removing
 * them would make those stores unreadable.
 */
public enum WorkflowKind {
    GREENFIELD(true),
    ENHANCEMENT(true),
    BUGFIX(true),
    REFACTOR(true),
    DOCS(false),
    ANALYSIS(false);

    private final boolean startable;

    WorkflowKind(boolean startable) {
        this.startable = startable;
    }

    /**
     * Whether an operator may start a run of this kind. False means the product must not offer it
     * anywhere: no command, no menu entry, no service call that accepts it.
     */
    public boolean isStartable() {
        return startable;
    }

    /** The kinds a run may be started as — the single source for menus, manuals and intake. */
    public static List<WorkflowKind> startable() {
        return Arrays.stream(values()).filter(WorkflowKind::isStartable).toList();
    }
}
