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
 * Shared signals for the backlog-planning flow. {@link #CURRENT} holds the current project's
 * planning flow as a {@link FlowView} — server-authoritative, {@code set()} after every state change
 * (a step completing, a round of questions arriving, story proposals being ready, a failure).
 *
 * <p>A signal of its own rather than a second publisher onto
 * {@link GuidedFlowSignals#CURRENT}: the two flows run independently and are visible at the same
 * time — intake behind the Requirements panel, planning behind the backlog — and sharing one signal
 * would make each publish overwrite the other wizard's frame with a flow it is not showing.
 *
 * <p>Publishers MUST {@code set()} a fresh deep copy — the signal dedups by {@code equals()}, so
 * mutating the stored flow in place and re-setting the same instance broadcasts nothing.
 *
 * <p><b>Shared, not scoped, and that was checked.</b> ZeroZ Stack 0.6.0 added a second kind of
 * signal: one value per browser, per signed-in user or per tenant ({@code Signals.scoped(name,
 * initial, Scope.CLIENT)} and its siblings). Every signal in this package was examined against it
 * on the move to 0.7.0 and every one of them stayed shared, for the same underlying reason:
 * the planning run is a job on the server, exactly like the intake analysis beside it. It
 * keeps going when the window is closed, and its state is saved rather than held in a
 * screen, so there is one answer to "where has it got to" and it is not per-browser.
 */
public final class PlanningFlowSignals {

    /** The current project's backlog-planning flow. Server sets it; clients read it. */
    public static final ValueSignal<FlowView> CURRENT =
        Signals.shared("flow.planning", FlowView.none());

    private PlanningFlowSignals() {}
}
