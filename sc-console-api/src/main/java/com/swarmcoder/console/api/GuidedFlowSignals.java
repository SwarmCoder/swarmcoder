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
 * Shared signals for guided flows. {@link #CURRENT} holds the current project's intake flow as a
 * {@link FlowView} — server-authoritative, {@code set()} after every state change (a step completing,
 * a round of questions arriving, proposals being ready, a failure).
 *
 * <p>This is what makes the wizard a view rather than an owner: progress is real server state, never
 * a spinner animating next to a request that may already have failed, and a dialog opened late
 * receives the retained value immediately.
 *
 * <p>Publishers MUST {@code set()} a fresh deep copy — the signal dedups by {@code equals()}, so
 * mutating the stored flow in place and re-setting the same instance broadcasts nothing.
 *
 * <p><b>Shared, not scoped, and that was checked.</b> ZeroZ Stack 0.6.0 added a second kind of
 * signal: one value per browser, per signed-in user or per tenant ({@code Signals.scoped(name,
 * initial, Scope.CLIENT)} and its siblings). Every signal in this package was examined against it
 * on the move to 0.7.0 and every one of them stayed shared, for the same underlying reason:
 * the analysis is a JOB RUNNING ON THE SERVER, not a dialog on a screen. It takes minutes,
 * it survives closing the window, and its answers and notes are saved as they are given.
 * What looks like "this operator's wizard position" is really "how far the server has got",
 * which is the same answer for anybody who asks. Making it per-browser would mean a second
 * window could not see the analysis that is running, which is precisely the thing this
 * signal exists to make possible.
 */
public final class GuidedFlowSignals {

    /** The current project's requirements-intake flow. Server sets it; clients read it. */
    public static final ValueSignal<FlowView> CURRENT =
        Signals.shared("flow.current", FlowView.none());

    private GuidedFlowSignals() {}
}
