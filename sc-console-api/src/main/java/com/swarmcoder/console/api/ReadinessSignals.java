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
 * Shared signal carrying {@link ConsoleReadiness} — what the Console can currently do.
 *
 * <p>A signal rather than a one-shot call because readiness CHANGES while the operator works:
 * creating a project, pointing it at a repository, or switching projects all move it, and the shell
 * has to follow without a reload. Server-authoritative and retained, so a view that mounts later
 * still gets the current answer.
 *
 * <p>Publishers must set a freshly built value — {@code set()} dedups by {@code equals}, so
 * republishing an instance the signal already holds is a silent no-op.
 *
 * <p><b>Shared, not scoped, and that was checked.</b> ZeroZ Stack 0.6.0 added a second kind of
 * signal: one value per browser, per signed-in user or per tenant ({@code Signals.scoped(name,
 * initial, Scope.CLIENT)} and its siblings). Every signal in this package was examined against it
 * on the move to 0.7.0 and every one of them stayed shared, for the same underlying reason:
 * every fact in it is about the orchestrator, not about a browser: whether a repository is
 * configured, whether the sandbox answered, how many requirements have been agreed. None of
 * those can differ between two windows onto the same process.
 */
public final class ReadinessSignals {

    /** What the Console can do right now. Server sets it; the shell renders from it. */
    public static final ValueSignal<ConsoleReadiness> CURRENT =
        Signals.shared("console.readiness", ConsoleReadiness.empty());

    private ReadinessSignals() {}
}
