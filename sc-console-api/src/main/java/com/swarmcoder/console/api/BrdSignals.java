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

import com.swarmcoder.domain.Brd;
import com.zeroz4j.signals.Signals;
import com.zeroz4j.signals.ValueSignal;

import java.util.ArrayList;

/**
 * Shared signals for the BRD. {@link #CURRENT} holds the current project's BRD graph — the domain
 * {@link Brd} object travels on the wire directly (@DataModel), no DTO. It is server-authoritative:
 * the server {@code set()}s it after any mutation (editor or the intake wizard) and the
 * framework broadcasts + retains the latest value, so the Requirements editor binds an
 * {@code Effect} to it and redraws live. A late-opening editor receives the retained value.
 *
 * <p><b>Shared, not scoped, and that was checked.</b> ZeroZ Stack 0.6.0 added a second kind of
 * signal: one value per browser, per signed-in user or per tenant ({@code Signals.scoped(name,
 * initial, Scope.CLIENT)} and its siblings). Every signal in this package was examined against it
 * on the move to 0.7.0 and every one of them stayed shared, for the same underlying reason:
 * one project has one requirement graph, and every browser looking at this console is
 * looking at that project. The revision number is a property of the document, not of who is
 * reading it.
 */
public final class BrdSignals {

    /** The current project's BRD graph. Server sets it; clients read it. */
    public static final ValueSignal<Brd> CURRENT = Signals.shared("brd.current",
        new Brd(null, null, 0, "Business Requirements", new ArrayList<>(), new ArrayList<>(), null, null));

    /**
     * "Something changed, the requirements are now at revision N" — two fields instead of the whole
     * document (see {@code docs/REQUIREMENTS_AT_SCALE_DESIGN.md} §5).
     *
     * <p>This is the signal a surface should bind to when it renders a bounded view: it re-queries the
     * page it is showing rather than receiving a graph whose size it does not control. {@link #CURRENT}
     * is still published beside it, because the canvas editor legitimately wants a whole graph and the
     * revision preview renders a historic one — but nothing that pages should be listening to it.
     */
    public static final ValueSignal<BrdVersion> VERSION =
        Signals.shared("brd.version", new BrdVersion(null, 0));

    private BrdSignals() {}
}
