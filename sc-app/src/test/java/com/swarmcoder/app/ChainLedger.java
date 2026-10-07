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
package com.swarmcoder.app;

import java.util.ArrayList;
import java.util.List;

/**
 * The links of the delivery chain, in order, each with what was actually OBSERVED at it.
 *
 * <h2>Why this is not a list of assertions</h2>
 *
 * <p>The chain is: document → requirements → rules → stories → plan → acceptance tests → workers →
 * candidates → verification → judge → selection → integration. Every one of those links broke on
 * its own during the last week, and every break hid the next: the run went green-ish, the operator
 * set the next project up by hand, watched it stop somewhere else, and reported back. Ten rounds,
 * twenty minutes each, one defect found per round.
 *
 * <p>An ordinary AssertJ failure is no use for that job. {@code expected: true but was: false} says
 * which line failed and nothing about what the machine was actually holding, so somebody still has
 * to open a log. This ledger says both, in one line, at the top of the output:
 *
 * <pre>
 * CHAIN BROKE AT [the acceptance stage executes a non-zero number of tests]
 *   observed: candidate 0 verified with acceptance stageOutcome=EXECUTED and 0 tests executed
 * </pre>
 *
 * <p>Every link that ran before it is printed above with its own observation, so a reader can see
 * how far the journey got without reading anything else, and every link after it is printed as NOT
 * REACHED rather than as a pass.
 *
 * <h2>The observation is written to be true either way</h2>
 *
 * <p>{@link #require} takes ONE observation string, not a pass message and a fail message. That is
 * deliberate: it forces the observation to be a measurement ("3 candidates, 0 carrying a
 * verification report") rather than a verdict ("verification worked"), and a measurement is the
 * thing that is worth reading when the link passes as well as when it fails. The recurring failure
 * this whole harness exists to catch is a stage that exits 0 having done nothing — so the numbers
 * are the evidence, and the boolean is only the alarm.
 *
 * <h2>A link restored from a snapshot is not a link that held (2026-09-25)</h2>
 *
 * <p>The live harness can start a run from a snapshot saved at the moment an earlier run was about
 * to dispatch its workers ({@code -Dswarmcoder.e2e.resumeFrom}), so the workers can be iterated on
 * without paying 20 to 40 minutes of local-model time for the front half every round. The links
 * before the workers were then walked by THAT run, not this one. {@link #restored} records them
 * with the observation that run made, printed as {@code [CHAIN saved]} and reported as
 * {@code saved}, never as {@code held}; {@link #whole} is false for a walk containing one; and
 * {@link #verdict} for such a walk never says "all links held". A reader who sees a green chain
 * must be able to trust that every link on it was measured in the run in front of them, or be told
 * plainly which were not.
 */
final class ChainLedger {

    /** A link that did not hold. Carries the two strings that make the one-line verdict. */
    static final class Broken extends AssertionError {
        final String link;
        final String observation;

        Broken(String link, String observation) {
            super("CHAIN BROKE AT [" + link + "]\n  observed: " + observation);
            this.link = link;
            this.observation = observation;
        }
    }

    /** How a link got onto the ledger: measured in this walk, or carried over from a snapshot. */
    private enum Status { HELD, BROKE, SAVED }

    private record Entry(String name, Status status, String observation) {}

    /** A link walked and held in THIS run, with what was measured — what a snapshot records. */
    record Held(String name, String observation) {}

    private final List<Entry> entries = new ArrayList<>();
    /** Every link the chain HAS, in order — so a break can say what was never reached. */
    private final List<String> plan;
    private Broken broken;

    ChainLedger(List<String> plan) {
        this.plan = List.copyOf(plan);
    }

    /**
     * Records a link and, when it did not hold, stops the walk.
     *
     * @param name        the link, in the words of the chain — no class names, no codes
     * @param held        whether it held
     * @param observation what was measured, phrased so it reads correctly either way
     */
    void require(String name, boolean held, String observation) {
        entries.add(new Entry(name, held ? Status.HELD : Status.BROKE, observation));
        System.out.println((held ? "[CHAIN ok  ] " : "[CHAIN FAIL] ") + name + " — " + observation);
        if (!held) {
            broken = new Broken(name, observation);
            throw broken;
        }
    }

    /** Records a link that held, with what was measured at it. */
    void ok(String name, String observation) {
        require(name, true, observation);
    }

    /**
     * Records a link this walk did NOT measure: an earlier run walked it and saved a snapshot, and
     * this run started from that snapshot.
     *
     * @param name        the link, in the words of the chain
     * @param observation what the run that saved the snapshot measured there, verbatim
     * @param provenance  which snapshot, when, and at which SwarmCoder commit — printed with it
     */
    void restored(String name, String observation, String provenance) {
        String line = observation + " [NOT walked in this run — restored from " + provenance + "]";
        entries.add(new Entry(name, Status.SAVED, line));
        System.out.println("[CHAIN saved] " + name + " — " + line);
    }

    /**
     * The links walked and held in THIS run so far, in order. Restored links are left out on
     * purpose: a snapshot saved from a resumed walk would otherwise pass a second-hand observation
     * off as a first-hand one.
     */
    List<Held> heldSoFar() {
        List<Held> held = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.status() == Status.HELD) {
                held.add(new Held(entry.name(), entry.observation()));
            }
        }
        return held;
    }

    /** Breaks the chain outright — for a link that cannot even be measured. */
    Broken fail(String name, String observation) {
        entries.add(new Entry(name, Status.BROKE, observation));
        System.out.println("[CHAIN FAIL] " + name + " — " + observation);
        broken = new Broken(name, observation);
        return broken;
    }

    /** True when every link in the plan was reached and held IN THIS RUN — none restored. */
    boolean whole() {
        return broken == null && entries.size() >= plan.size() && restoredCount() == 0;
    }

    private int restoredCount() {
        return (int) entries.stream().filter(e -> e.status() == Status.SAVED).count();
    }

    /** The one line somebody reads instead of a log. */
    String verdict() {
        if (broken != null) {
            return "CHAIN BROKE AT [" + broken.link + "] — observed: " + broken.observation;
        }
        int restored = restoredCount();
        if (entries.size() < plan.size()) {
            return "CHAIN INCOMPLETE — " + (entries.size() - restored) + " of " + plan.size()
                + " links walked" + (restored == 0 ? "" : " (and " + restored
                    + " restored from a snapshot, not walked)")
                + "; never reached [" + plan.get(entries.size()) + "]";
        }
        if (restored > 0) {
            return "CHAIN WHOLE FROM A SNAPSHOT — the last " + (plan.size() - restored) + " of "
                + plan.size() + " links held in this run; the first " + restored
                + " were restored from a snapshot and were NOT walked in it";
        }
        return "CHAIN WHOLE — all " + plan.size() + " links held, document to merged commit";
    }

    /** The whole walk, for the console: what held, what broke, what was never reached. */
    String report() {
        StringBuilder sb = new StringBuilder("\n==================== END-TO-END CHAIN ====================\n");
        for (int i = 0; i < plan.size(); i++) {
            String name = plan.get(i);
            Entry entry = i < entries.size() && entries.get(i).name().equals(name)
                ? entries.get(i) : null;
            if (entry == null) {
                sb.append(String.format("%2d. NOT REACHED  %s%n", i + 1, name));
            } else {
                String status = switch (entry.status()) {
                    case HELD -> "held  ";
                    case BROKE -> "BROKE ";
                    case SAVED -> "saved ";
                };
                sb.append(String.format("%2d. %s  %s%n      %s%n", i + 1, status, name,
                    entry.observation()));
            }
        }
        sb.append("----------------------------------------------------------\n")
          .append(verdict())
          .append("\n==========================================================\n");
        return sb.toString();
    }
}
