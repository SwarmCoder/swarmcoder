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

import com.zeroz4j.ui.component.Js;
import com.zeroz4j.signals.ValueSignal;

/**
 * Whether the developer surfaces (Prompt Lab, Components) are offered at all.
 *
 * <p>Author decision 2026-07-27: side surfaces split by audience. Prompt Lab and the component
 * gallery are things a person building SwarmCoder needs; they are not steps an operator has to take,
 * and leaving them in the navigation is what made the shell read as a pile of unrelated tools. They
 * go behind a toggle in Settings and are off the operator's navigation entirely until it is on.
 *
 * <p>Persisted client-side in {@code localStorage}, exactly the way the theme already is: this is a
 * preference about one browser, not a fact about the project, so putting it on the server would make
 * one person's debugging session everybody's console.
 */
final class DevTools {

    private static final String KEY = "console.devtools";

    /** True when the developer surfaces are shown. Read by the stage bar and the command palette. */
    static final ValueSignal<Boolean> enabled = new ValueSignal<>("1".equals(Js.localGet(KEY)));

    private DevTools() {}

    /** Flips the preference and persists it, so it survives a reload like the theme does. */
    static void set(boolean on) {
        Js.localSet(KEY, on ? "1" : "0");
        enabled.set(on);
    }

    static boolean isEnabled() {
        return Boolean.TRUE.equals(enabled.get());
    }
}
