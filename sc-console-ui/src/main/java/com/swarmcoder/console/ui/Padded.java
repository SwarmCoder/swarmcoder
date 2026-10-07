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

import com.zeroz4j.api.Disposable;
import com.zeroz4j.ui.component.Component;
import com.zeroz4j.ui.layout.Div;

/**
 * Page padding for the views ported from the v1 vertical stack, which still expect it.
 *
 * <p>It exists as a class rather than a helper method because of disposal. A plain wrapper {@code
 * Div} is what the tab machinery sees, so {@code instanceof Disposable} tests the WRAPPER — and the
 * view inside, the one actually holding effects bound to process-wide signals, is silently never
 * torn down. Delegating here is the difference between a stage swap that releases its subscriptions
 * and one that leaks every one of them.
 */
final class Padded extends Div implements Disposable {

    private final Component view;

    Padded(Component view) {
        this.view = view;
        addClassName("p-4 max-w-5xl");
        add(view);
    }

    @Override
    public void dispose() {
        if (view instanceof Disposable disposable) {
            disposable.dispose();
        }
    }
}
