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
package com.swarmcoder.console.api;

import com.zeroz4j.signals.Signals;
import com.zeroz4j.signals.ValueSignal;

/**
 * Shared signal for the backlog. {@link #CURRENT} holds the current project's iterations, stories
 * and their tasks, carrying the domain {@link com.swarmcoder.domain.Story} and
 * {@link com.swarmcoder.domain.Iteration} objects directly — no mirror DTOs.
 *
 * <p>Server-authoritative: the server {@code set()}s it after any mutation (the panel, the
 * {@code /backlog} agent, or a task changing state mid-run) and the framework broadcasts and retains
 * the latest value, so the panel binds an {@code Effect} and updates live. A panel opened late gets
 * the retained value.
 *
 * <p>Publishers MUST set a freshly built {@link Backlog}: {@code set()} dedups by {@code equals}, so
 * republishing a mutated instance that is already the signal's value is a silent no-op and the UI
 * would simply not update.
 *
 * <p><b>Shared, not scoped, and that was checked.</b> ZeroZ Stack 0.6.0 added a second kind of
 * signal: one value per browser, per signed-in user or per tenant ({@code Signals.scoped(name,
 * initial, Scope.CLIENT)} and its siblings). Every signal in this package was examined against it
 * on the move to 0.7.0 and every one of them stayed shared, for the same underlying reason:
 * the console is a window onto ONE orchestrator process, and that process has one current
 * project with one backlog in it. Two browsers pointed at this console are looking at the
 * same iterations, the same stories and the same tasks, because there is only one set. A
 * per-browser backlog would be a different product.
 */
public final class BacklogSignals {

    /** The current project's backlog. Server sets it; clients read it. */
    public static final ValueSignal<Backlog> CURRENT =
        Signals.shared("backlog.current", Backlog.empty());

    private BacklogSignals() {}
}
