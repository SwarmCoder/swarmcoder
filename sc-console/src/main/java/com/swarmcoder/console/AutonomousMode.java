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
package com.swarmcoder.console;

import com.swarmcoder.console.api.AutonomousSignals;
import com.swarmcoder.console.api.AutonomousStatus;
import com.swarmcoder.console.api.BudgetsDto;
import com.swarmcoder.domain.AutonomousDecision;
import com.swarmcoder.domain.AutonomousDecisionKind;
import com.swarmcoder.store.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The switch behind "build all of this without asking me", and the one place that knows whether it
 * is on.
 *
 * <p><b>What it turns on.</b> Ordinary unattended running does the back half of the pipeline: accept
 * a story the machine has already proved finished, start the next one whose predecessors are
 * delivered. This turns on the front half as well - reading the operator's documents, answering the
 * analyst's clarifications, agreeing the requirements, planning the stories and marking them ready -
 * so that a loaded document reaches a running build with nobody present. {@link AutonomousBuild}
 * does that work; this only says whether it may.
 *
 * <p><b>Why a session rather than a setting.</b> Unattended running is a line in the settings file,
 * because it is a standing preference. This is not a preference. It is an act: the operator presses
 * a button, is told exactly what will be decided for them, and it runs until it is stopped, or the
 * budget runs out, or the clock does. It is deliberately held in memory only - restarting
 * SwarmCoder ends it. A mode that came back by itself after a crash nobody watched is exactly the
 * thing that must not exist here.
 *
 * <p><b>Why the record is not optional.</b> A clarifying question exists because the document did
 * not say. Answering one is not looking something up, it is deciding what the product does. Every
 * such decision is written to {@link AutonomousDecision} as it is taken, with a flag saying whether
 * it came out of the operator's documents or was made up - see that class. Nothing here may act
 * without writing that line first.
 *
 * <p><b>The ceilings are the ones the settings file already has.</b> {@code budgets.wallClockCeilingHours}
 * and {@code budgets.maxCloudTokensPerRun}, read at the moment the button is pressed. There is no
 * third number to set and no autonomous-only budget to forget about: an operator who has already
 * capped what a run may spend has capped what a night may spend.
 *
 * <p><b>It runs in the open.</b> Whether it is on, which gate it is at, and which project it is
 * driving are pushed onto {@link com.swarmcoder.console.api.AutonomousSignals} every time any of
 * them changes, so all three can be across the top of whatever screen the operator is using.
 * Nothing here depends on a window being open and nothing here ever did — but for its first week the
 * only place it could be seen was inside the window that armed it, which is how a run that was
 * working came to be indistinguishable from one that had hung.
 *
 * <p><b>Nothing in the Console is disabled while this is on, and that was a decision.</b> The
 * operator asked whether features ought to be switched off, having also said that the point of the
 * mode is to watch the work and still open things. Reading is obviously safe. What was checked is
 * whether any WRITE genuinely collides, and none of them does:
 *
 * <ul>
 *   <li>Agreeing a requirement the pilot was about to agree: the pilot agrees whatever is still a
 *       draft, so one the operator agreed first is simply not a draft when it looks. The agreement
 *       gate refuses anything untestable either way, whoever pressed it.</li>
 *   <li>Starting or accepting a story: {@code startSession} refuses a story that is not READY and
 *       the pilot already treats a refusal as "skip this one and carry on" — by design, since a
 *       night must survive one story going wrong. A person getting there first is that same case.</li>
 *   <li>Answering a build that stopped to ask something: the pilot deliberately never answers those,
 *       so the operator is the only actor there and there is nothing to collide with.</li>
 *   <li>Editing a requirement that has already been planned against: a real hazard, and exactly as
 *       real in a manual run and under ordinary unattended running. It is not this switch's to fix,
 *       and pretending it is by greying out the editor here would hide it everywhere else.</li>
 * </ul>
 *
 * <p>So the operator outranks the pilot and nothing is taken away. The one case that DID need
 * handling is not a collision at all: a session is bound to the project it was started on and goes
 * quiet for any other, so the strip names the project it is driving and the screen says plainly when
 * that is not the one the operator has open.
 */
public final class AutonomousMode {

    private static final Logger log = LoggerFactory.getLogger(AutonomousMode.class);

    /** Used when the settings file names no wall-clock ceiling. A night, not a week. */
    static final int DEFAULT_CEILING_HOURS = 12;

    private AutonomousMode() {}

    /**
     * One stretch of running unattended: when it started, what it may spend, and how it ended.
     *
     * <p>Mutable and shared between the console thread that starts and stops it and the pilot thread
     * that reads it, so every field that changes after construction is volatile.
     */
    public static final class Session {
        private final UUID id = UUID.randomUUID();
        private final UUID projectId;
        private final Instant startedAt = Instant.now();
        private final Instant deadline;
        /** Cloud tokens already spent when this started, so the ceiling is about THIS session. */
        private final long tokensAtStart;
        /** Absolute cloud-token reading at which this stops, or 0 when nothing is capped. */
        private final long tokenCeiling;
        private volatile boolean running = true;
        private volatile String stoppedBecause;
        /** What it is doing right now, in the operator's words. */
        private volatile String activity = "Starting";

        Session(UUID projectId, Instant deadline, long tokensAtStart, long tokenCeiling) {
            this.projectId = projectId;
            this.deadline = deadline;
            this.tokensAtStart = tokensAtStart;
            this.tokenCeiling = tokenCeiling;
        }

        public UUID id() { return id; }
        public UUID projectId() { return projectId; }
        public Instant startedAt() { return startedAt; }
        public Instant deadline() { return deadline; }
        public long tokensAtStart() { return tokensAtStart; }
        public long tokenCeiling() { return tokenCeiling; }
        public boolean running() { return running; }
        public String stoppedBecause() { return stoppedBecause; }
        public String activity() { return activity; }

        void setActivity(String activity) {
            this.activity = activity;
        }
    }

    private static final AtomicReference<Session> CURRENT = new AtomicReference<>();

    /** The stretch that is running or was last run, or null on a process that has never run one. */
    public static Session current() {
        return CURRENT.get();
    }

    /** True while the machine is entitled to decide things on the operator's behalf. */
    public static boolean isRunning() {
        Session session = CURRENT.get();
        return session != null && session.running();
    }

    /** The running session for this project, or null - the pilot's own guard. */
    static Session runningFor(UUID projectId) {
        Session session = CURRENT.get();
        return session != null && session.running() && session.projectId().equals(projectId)
            ? session : null;
    }

    /**
     * Switches it on for the current project. Returns "" or "error: ...".
     *
     * <p>Refused when one is already running, including for another project: two of these at once
     * would each be deciding requirements while the other spent the same budget, and neither
     * operator would be able to tell afterwards which night did what.
     */
    public static String start() {
        ConsoleContext context = ConsoleContext.get();
        if (context == null) {
            return "error: the console is not wired up yet";
        }
        UUID projectId = context.currentProjectId();
        if (projectId == null) {
            return "error: there is no project open, and everything belongs to one";
        }
        if (isRunning()) {
            return "error: it is already running - stop it before starting it again";
        }
        if (context.analystModel() == null) {
            return "error: no analyst model is configured, so nothing can read your documents. "
                + "Set roles.requirementsAnalyst (or roles.chat) in Settings first.";
        }
        int hours = ceilingHours(context);
        long used = spentSoFar(context);
        long cap = tokenCap(context);
        Session session = new Session(projectId, Instant.now().plus(Duration.ofHours(hours)),
            used, cap <= 0 ? 0 : cap);
        CURRENT.set(session);
        publish();
        log.info("Autonomous mode ON for project {} - stops at {} or at {} cloud tokens spent in "
            + "total ({} already spent)", projectId, session.deadline(),
            session.tokenCeiling() == 0 ? "no cap" : session.tokenCeiling(), used);
        // Nothing is written to the diary here on purpose: starting is the operator's own act,
        // not a decision taken on their behalf. The diary begins at the first thing the machine
        // chooses for them.
        return "";
    }

    /**
     * Switches it off and says why on the record. Safe to call when nothing is running.
     *
     * @param reason in the operator's words - it is shown as the last line of the night's diary
     */
    public static String stop(String reason) {
        Session session = CURRENT.get();
        if (session == null || !session.running()) {
            return "";
        }
        session.running = false;
        session.stoppedBecause = reason == null || reason.isBlank()
            ? "You stopped it." : reason.strip();
        session.setActivity("Stopped");
        publish();
        log.info("Autonomous mode OFF: {}", session.stoppedBecause);
        ConsoleContext context = ConsoleContext.get();
        if (context != null) {
            try {
                write(context.store(), session, AutonomousDecisionKind.STOPPED,
                    "Autonomous running", null, session.stoppedBecause,
                    "Nothing further will be decided for you until you start it again.", true,
                    null, null);
            } catch (Exception e) {
                // The stop itself has already happened; failing to write the last line must not
                // leave the switch on.
                log.warn("Could not record why autonomous mode stopped: {}", e.toString());
            }
        }
        return "";
    }

    /**
     * Why this session must stop now, or null when it may carry on.
     *
     * <p>Checked at the top of every tick, before anything is decided or spent. Both numbers come
     * from the settings file the operator already keeps.
     */
    static String stopReason(ConsoleContext context, Session session) {
        if (Instant.now().isAfter(session.deadline())) {
            return "It ran for the " + ceilingHours(context) + " hours you allow a single run "
                + "(budgets.wallClockCeilingHours) and stopped. Anything it had started is still "
                + "running; nothing new was begun.";
        }
        if (session.tokenCeiling() > 0 && spentSoFar(context) >= session.tokenCeiling()) {
            return "It reached the spending limit you set (budgets.maxCloudTokensPerRun, "
                + session.tokenCeiling() + " tokens) and stopped before going over it.";
        }
        return null;
    }

    /** Halts the session with a reason, from the pilot's own thread. */
    static void halt(String reason) {
        stop(reason);
    }

    // --- the record ---------------------------------------------------------------------------

    /**
     * Writes one decision to the night's diary.
     *
     * <p>Every call site is a thing the operator would otherwise have been asked. Nothing acts
     * before this returns: a decision taken and not written is the failure this whole record exists
     * to prevent.
     */
    static AutonomousDecision write(ArtifactStore store, Session session,
                                    AutonomousDecisionKind kind, String subject, String question,
                                    String answer, String reasoning, boolean grounded,
                                    UUID flowId, UUID questionId) {
        AutonomousDecision decision = new AutonomousDecision(UUID.randomUUID(), session.projectId(),
            session.id(), Instant.now(), kind, subject, question, answer, reasoning, grounded,
            flowId, questionId);
        store.recordAutonomousDecision(decision);
        log.info("Autonomous: {} - {} :: {}", decision.headline(), subject, answer);
        return decision;
    }

    // --- the numbers, read from the settings the operator already keeps -------------------------

    static int ceilingHours(ConsoleContext context) {
        BudgetsDto budgets = budgets(context);
        int hours = budgets == null ? 0 : budgets.getWallClockCeilingHours();
        return hours > 0 ? hours : DEFAULT_CEILING_HOURS;
    }

    static long tokenCap(ConsoleContext context) {
        BudgetsDto budgets = budgets(context);
        return budgets == null ? 0 : budgets.getMaxCloudTokensPerRun();
    }

    private static BudgetsDto budgets(ConsoleContext context) {
        try {
            ConsoleContext.ConfigForms forms = context.configForms();
            return forms == null ? null : forms.budgets();
        } catch (Exception e) {
            // An unreadable settings file is not a licence to run without a ceiling; the caller
            // falls back on the default night.
            log.warn("Could not read the budgets from settings: {}", e.toString());
            return null;
        }
    }

    /** Cloud tokens this process has spent in total, from the same gate the health strip reads. */
    static long spentSoFar(ConsoleContext context) {
        try {
            return context.health().budgetUsed();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * One sentence saying what is happening, for the button and the strip above the board.
     *
     * <p>Written here so the browser never assembles its own version of it.
     */
    public static String statusLine() {
        Session session = CURRENT.get();
        if (session == null) {
            return "Not running. Nothing is being decided for you.";
        }
        if (!session.running()) {
            return "Stopped. " + (session.stoppedBecause() == null
                ? "" : session.stoppedBecause());
        }
        ConsoleContext context = ConsoleContext.get();
        long spent = context == null ? 0 : spentSoFar(context) - session.tokensAtStart();
        long minutes = Duration.between(session.startedAt(), Instant.now()).toMinutes();
        String line = "Running on its own. " + session.activity() + ". "
            + minutes + (minutes == 1 ? " minute" : " minutes") + " so far, "
            + spent + " tokens spent, stops by "
            + session.deadline().toString().substring(11, 16) + " UTC at the latest.";
        // If a model server is running fewer workers than it started with, the strip says so —
        // it is the one line the operator reads about a night, and fewer chips with no sentence
        // beside them would read as the system winding down.
        java.util.List<String> throttled =
            com.swarmcoder.inference.AdaptiveConcurrency.shared().throttled();
        return throttled.isEmpty() ? line : line + " " + String.join(" ", throttled);
    }

    /**
     * Puts the same two sentences on every screen the operator might be standing on.
     *
     * <p>Called when it is switched on, when it is switched off, and once at the end of every tick
     * the pilot spends on it — see {@link UnattendedPilot#tick()}. The tick is the right cadence
     * because the tick is when anything changes: the gate moves inside a tick and at no other
     * moment, and between ticks the only thing that moves is the clock, which the sentence carries.
     * A tick that changed nothing at all costs nothing, because the signal dedups by value.
     *
     * <p>Best-effort on purpose. A night must not end because a browser could not be told about it.
     */
    static void publish() {
        try {
            Session session = CURRENT.get();
            boolean live = session != null && session.running();
            AutonomousSignals.CURRENT.set(new AutonomousStatus(live,
                live ? session.activity() : "", statusLine(),
                live ? projectName(session.projectId()) : ""));
        } catch (Exception e) {
            log.warn("Could not publish what autonomous running is doing: {}", e.toString());
        }
    }

    /**
     * The name of the project a session is driving, or "" when it cannot be looked up.
     *
     * <p>The id is what the pilot works from; the name is what a person recognises, and the screen
     * that shows this has to compare it against the project the operator has open. Best-effort: a
     * missing name costs the comparison, never the publish.
     */
    private static String projectName(UUID projectId) {
        try {
            ConsoleContext context = ConsoleContext.get();
            if (context == null || projectId == null) {
                return "";
            }
            for (com.swarmcoder.domain.Project project : context.listProjects()) {
                if (projectId.equals(project.id())) {
                    return project.name() == null ? "" : project.name();
                }
            }
        } catch (Exception e) {
            log.warn("Could not name the project autonomous running is driving: {}", e.toString());
        }
        return "";
    }

    /** Clears the session. Tests only - production has one process and one switch. */
    static void reset() {
        CURRENT.set(null);
        publish();
    }
}
