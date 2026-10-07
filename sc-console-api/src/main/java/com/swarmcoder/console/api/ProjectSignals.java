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
 * Shared signal for the project registry. {@link #CURRENT} holds every project plus which one is
 * current, and the left project rail binds an {@code Effect} to it.
 *
 * <p>Server-authoritative, exactly like {@link BrdSignals} and {@link BacklogSignals}: the server
 * {@code set()}s it when the Console is wired and after any change (create / switch / project
 * settings saved), and the framework broadcasts and retains the latest value, so a client that
 * connects later is sent the retained value the moment it subscribes.
 *
 * <p>This replaces a single {@code control.projects()} RMI call made from the rail's constructor.
 * That call runs during client bootstrap — a native JS callback, not a TeaVM green thread — so it
 * threw {@code "Suspension point reached from non-threading context"} every time and the rail
 * stayed permanently empty because nothing ever retried. A shared signal has no such constraint:
 * receiving a broadcast is not a suspending call.
 *
 * <p>Publishers MUST set a freshly built {@link ProjectList}; see the note on that class.
 *
 * <p><b>Shared, not scoped, and that was checked.</b> ZeroZ Stack 0.6.0 added a second kind of
 * signal: one value per browser, per signed-in user or per tenant ({@code Signals.scoped(name,
 * initial, Scope.CLIENT)} and its siblings). Every signal in this package was examined against it
 * on the move to 0.7.0 and every one of them stayed shared, for the same underlying reason:
 * the list is server-wide, and so, today, is the "which one is current" flag on it — the
 * orchestrator holds a single current project and every service resolves against it.
 *
 * <p><b>This is the one worth a conversation.</b> Because the flag is server-wide, one
 * operator pressing "switch project" moves EVERY open browser to the new project, and every
 * other signal here re-scopes with it. The framework now has the piece that would fix it —
 * a per-browser identity that needs no login — but the fix is not in this class. It would
 * mean the server resolving the current project from the asking connection instead of from
 * one field, which is a change to what the orchestrator is, not to how a signal is
 * declared. Left alone deliberately, and reported.
 */
public final class ProjectSignals {

    /** Every project, with the current one flagged. Server sets it; clients read it. */
    public static final ValueSignal<ProjectList> CURRENT =
        Signals.shared("projects.current", ProjectList.empty());

    private ProjectSignals() {}
}
