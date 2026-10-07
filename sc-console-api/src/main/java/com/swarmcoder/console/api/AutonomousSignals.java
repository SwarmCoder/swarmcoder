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
 * Shared signal carrying {@link AutonomousStatus} — whether the machine is deciding things for the
 * operator, and which gate it is at.
 *
 * <p>A signal rather than the request/response pair it replaces on screen. The window that armed
 * autonomous running asked the server every four seconds while it was open, and asked nothing at
 * all once it was closed — so the one fact the operator most wanted ("is it still on, and is it
 * moving?") was visible only in the one place they could not work from. Pushed, it can be on the
 * header of every screen and cost nothing when nothing is happening.
 *
 * <p>{@code ControlService.autonomousRunning()} and {@code autonomousStatus()} still exist and
 * still answer; both read the same session this is published from, so there is one derivation and
 * two ways of asking for it. Nothing renders from both.
 *
 * <p><b>Shared, not scoped.</b> For the same reason every other signal in this package is: one
 * orchestrator process has one autonomous session at a time, by construction — starting a second
 * one is refused. Two browsers onto this console are looking at the same night's work.
 *
 * <p>Publishers must set a freshly built value: {@code set()} dedups by {@code equals}, which is
 * exactly what makes a tick that changed nothing free.
 */
public final class AutonomousSignals {

    /** What the machine is deciding right now. The server sets it; every screen may read it. */
    public static final ValueSignal<AutonomousStatus> CURRENT =
        Signals.shared("console.autonomous", AutonomousStatus.off());

    private AutonomousSignals() {}
}
