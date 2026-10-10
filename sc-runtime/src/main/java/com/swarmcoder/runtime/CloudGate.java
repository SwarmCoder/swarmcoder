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
package com.swarmcoder.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The one gate every cloud-role call passes through (spec §6.2, rule R6): mechanical enforcement of
 * the cloud token limits. The whole business case is that cloud spend scales with task count, not
 * implementation tokens - the gate keeps that true even when a bug retries in a loop.
 *
 * <p><b>What counts.</b> Only the cloud roles are wired to this gate (architect, planner, test
 * author, reviewer, judge, expert, utility). Workers run on the local model server and never charge
 * it, so their tokens never count. Accounting is estimate-based (chars/4) until the inference
 * clients parse usage frames; estimates only ever need to be the right order of magnitude.
 *
 * <p><b>Per run, per story, per project.</b> Until 2026-10-10 there was ONE counter for the whole
 * process, never reset, so "tokens per run" was really "tokens since the process started" and the
 * decision it raised named no run. Now the thread that drives a run enters {@link #enter(Where)}
 * and every charge made on that thread (and on the virtual threads it starts, which inherit the
 * scope) is added to that run's tally, to its story's and to its project's. Each of the three may
 * carry a limit, and each limit may be given for input tokens, output tokens, or both together
 * ({@link Cap}). A charge made outside any run is counted in {@link #outsideRuns()} and limited by
 * nothing.
 *
 * <p><b>Input and output.</b> {@link #charge(long)} is what was sent to the model - or, for the
 * agent sessions that only report one combined figure, that figure; those are counted as input
 * because a session's cost is the conversation it resends every turn. {@link #chargeOutput(long)}
 * is what the model wrote back.
 *
 * <p><b>Extending.</b> A limit that was passed is raised by {@link #extendForRun(UUID)}: the same
 * amount again, for the run, story or project whose limit was passed. That is the whole answer to
 * an {@code extend} reply; resuming the parked run is the workflow's own re-drive
 * ({@code WorkflowEngine.advance}).
 *
 * <p>Nothing here is persisted: a restart starts every tally at zero.
 */
public final class CloudGate {

    /** What a limit applies to. */
    public enum Level { RUN, STORY, PROJECT }

    /** Which count a limit is on. */
    public enum Direction { TOTAL, INPUT, OUTPUT }

    /**
     * One level's limits; zero or less means "no limit" for that count.
     *
     * @param total  input and output together (the pre-2026-10 {@code maxCloudTokensPerRun} shape)
     * @param input  tokens sent to the models
     * @param output tokens the models wrote
     */
    public record Cap(long total, long input, long output) {
        public static final Cap NONE = new Cap(0, 0, 0);

        public static Cap total(long total) {
            return new Cap(total, 0, 0);
        }

        public long of(Direction direction) {
            return switch (direction) {
                case TOTAL -> total;
                case INPUT -> input;
                case OUTPUT -> output;
            };
        }

        public boolean any() {
            return total > 0 || input > 0 || output > 0;
        }
    }

    /** The limits of the three levels. */
    public record Limits(Cap run, Cap story, Cap project) {
        public static final Limits NONE = new Limits(Cap.NONE, Cap.NONE, Cap.NONE);

        public Limits {
            run = run == null ? Cap.NONE : run;
            story = story == null ? Cap.NONE : story;
            project = project == null ? Cap.NONE : project;
        }

        public Cap of(Level level) {
            return switch (level) {
                case RUN -> run;
                case STORY -> story;
                case PROJECT -> project;
            };
        }
    }

    /** Whose work a charge is: any part may be null (a story-less run, work outside a run). */
    public record Where(UUID projectId, UUID storyId, UUID runId) {
        UUID idOf(Level level) {
            return switch (level) {
                case RUN -> runId;
                case STORY -> storyId;
                case PROJECT -> projectId;
            };
        }
    }

    /** Tokens counted so far. */
    public record Spend(long input, long output) {
        public long total() {
            return input + output;
        }
    }

    /**
     * A limit that was passed.
     *
     * @param level       which limit: the run's, its story's or its project's
     * @param direction   which count of it: input, output, or both together
     * @param limit       the limit in force when it was passed (an extended limit included)
     * @param used        what that level had used when it was passed
     */
    public record Breach(Level level, Direction direction, UUID projectId, UUID storyId,
                         UUID runId, long limit, Spend used) {
        /** The id of the run, story or project whose limit it was. */
        public UUID scopeId() {
            return new Where(projectId, storyId, runId).idOf(level);
        }
    }

    /** What {@link #extendForRun(UUID)} raised, and to what. */
    public record Extension(Level level, UUID scopeId, Cap limitNow) {}

    /** Thrown when a charge passes a limit; callers park the run on a BUDGET_EXTENSION decision. */
    public static final class BudgetExhaustedException extends RuntimeException {
        private final transient Breach breach;

        public BudgetExhaustedException(long used, long max) {
            super("Cloud budget exhausted: " + used + " of " + max + " tokens used");
            this.breach = null;
        }

        BudgetExhaustedException(Breach breach) {
            super("Cloud " + breach.level().name().toLowerCase() + " budget exhausted ("
                + breach.direction().name().toLowerCase() + "): " + breach.used().input()
                + " input and " + breach.used().output() + " output tokens used, limit "
                + breach.limit());
            this.breach = breach;
        }

        /** Which limit was passed; null for a breach of the legacy, scope-less cap. */
        public Breach breach() {
            return breach;
        }
    }

    /** Leaves the scope entered with {@link #enter(Where)}; closing twice is harmless. */
    public interface Entered extends AutoCloseable {
        @Override
        void close();
    }

    private static final class Tally {
        final AtomicLong input = new AtomicLong();
        final AtomicLong output = new AtomicLong();
        final AtomicInteger extensions = new AtomicInteger();

        Spend spend() {
            return new Spend(input.get(), output.get());
        }

        long of(Direction direction) {
            return switch (direction) {
                case TOTAL -> input.get() + output.get();
                case INPUT -> input.get();
                case OUTPUT -> output.get();
            };
        }
    }

    private static final Logger log = LoggerFactory.getLogger(CloudGate.class);
    private static final InheritableThreadLocal<Where> HERE = new InheritableThreadLocal<>();

    private final Limits limits;
    /** The old process-wide cap, applied to charges made outside any run; 0 for none. */
    private final long unscopedCap;
    private final Consumer<Breach> onBreach;
    private final Runnable onUnscopedBreach;
    private final Tally all = new Tally();
    private final Tally unscoped = new Tally();
    private final Map<UUID, Tally> runs = new ConcurrentHashMap<>();
    private final Map<UUID, Tally> stories = new ConcurrentHashMap<>();
    private final Map<UUID, Tally> projects = new ConcurrentHashMap<>();
    private final Set<String> signalled = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Breach> lastBreach = new ConcurrentHashMap<>();
    private final AtomicBoolean unscopedSignalled = new AtomicBoolean();

    /**
     * @param limits   the three levels' limits; {@link Limits#NONE} enforces nothing
     * @param onBreach invoked once for each limit passed, for each run that passes it - wired to
     *                 queue a {@code DecisionKind.BUDGET_EXTENSION} decision for that run
     */
    public CloudGate(Limits limits, Consumer<Breach> onBreach) {
        this.limits = limits == null ? Limits.NONE : limits;
        this.unscopedCap = 0;
        this.onBreach = onBreach == null ? b -> { } : onBreach;
        this.onUnscopedBreach = () -> { };
        if (!this.limits.run().any() && !this.limits.story().any() && !this.limits.project().any()) {
            log.warn("Cloud budget limits disabled (no budgets.maxCloudTokens* limit is set)");
        }
    }

    /**
     * The one-cap shape: {@code cap} applies to charges made outside any run, as the single
     * process-wide cap always did, and to each run as its total limit.
     *
     * @param maxCloudTokens hard cap; {@code <= 0} disables enforcement (logged once)
     * @param onExhausted    invoked exactly once when the cap is first breached outside a run
     */
    public CloudGate(long maxCloudTokens, Runnable onExhausted) {
        this.limits = new Limits(Cap.total(Math.max(0, maxCloudTokens)), Cap.NONE, Cap.NONE);
        this.unscopedCap = Math.max(0, maxCloudTokens);
        this.onBreach = b -> { };
        this.onUnscopedBreach = onExhausted == null ? () -> { } : onExhausted;
        if (maxCloudTokens <= 0) {
            log.warn("Cloud budget cap disabled (budgets.maxCloudTokensPerRun <= 0)");
        }
    }

    // --- scope ----------------------------------------------------------------------------------

    /**
     * Makes {@code where} the owner of every charge made on this thread, and on threads it starts,
     * until the result is closed.
     */
    public static Entered enter(Where where) {
        Where before = HERE.get();
        HERE.set(where);
        return () -> {
            if (before == null) {
                HERE.remove();
            } else {
                HERE.set(before);
            }
        };
    }

    /** Whose work this thread is doing, or null. */
    public static Where current() {
        return HERE.get();
    }

    // --- charging -------------------------------------------------------------------------------

    /**
     * Records tokens sent to a model (or an agent session's one combined figure) and throws once a
     * limit is passed.
     */
    public void charge(long tokens) {
        add(Math.max(0, tokens), 0);
    }

    /** Records tokens a model wrote and throws once a limit is passed. */
    public void chargeOutput(long tokens) {
        add(0, Math.max(0, tokens));
    }

    private void add(long in, long out) {
        Where where = HERE.get();
        all.input.addAndGet(in);
        all.output.addAndGet(out);
        if (where == null) {
            unscoped.input.addAndGet(in);
            unscoped.output.addAndGet(out);
        }
        if (where == null) {
            // Work outside any run is only ever held to the old process-wide cap, when one was given
            // in that shape. Everything else is counted as "outside runs" and left alone.
            long total = unscoped.of(Direction.TOTAL);
            if (unscopedCap > 0 && total > unscopedCap) {
                if (unscopedSignalled.compareAndSet(false, true)) {
                    log.error("Cloud budget exhausted ({} of {} tokens) - parking BUDGET_EXTENSION "
                        + "decision", total, unscopedCap);
                    onUnscopedBreach.run();
                }
                throw new BudgetExhaustedException(total, unscopedCap);
            }
            return;
        }
        for (Level level : Level.values()) {
            Tally tally = tallyFor(level, where, true);
            if (tally != null) {
                tally.input.addAndGet(in);
                tally.output.addAndGet(out);
            }
        }
        Breach breach = passed(where);
        if (breach != null) {
            signal(breach);
            throw new BudgetExhaustedException(breach);
        }
    }

    /**
     * Gives back a charge for a call that never reached the model.
     *
     * <p>The gate charges the prompt BEFORE sending, so that a runaway loop is stopped before it
     * spends rather than after. That is right for calls that happen, and wrong for calls that do
     * not: when the endpoint is unreachable the workflow retries the same call until it returns
     * (UX v3 §2.4), and charging every one of those attempts would let an outage eat a whole run's
     * budget without a single token having been generated. Refunding keeps auto-retry inside the
     * original budget, which is the condition attached to it by rule 6.
     *
     * <p>Only ever called with an amount this caller just charged as input.
     */
    public void refund(long tokens) {
        if (tokens <= 0) {
            return;
        }
        take(all, tokens);
        if (HERE.get() == null) {
            take(unscoped, tokens);
        }
        Where where = HERE.get();
        if (where != null) {
            for (Level level : Level.values()) {
                Tally tally = tallyFor(level, where, false);
                if (tally != null) {
                    take(tally, tokens);
                }
            }
        }
    }

    private static void take(Tally tally, long tokens) {
        tally.input.updateAndGet(current -> Math.max(0, current - tokens));
    }

    private Tally tallyFor(Level level, Where where, boolean create) {
        UUID id = where.idOf(level);
        if (id == null) {
            return null;
        }
        Map<UUID, Tally> map = switch (level) {
            case RUN -> runs;
            case STORY -> stories;
            case PROJECT -> projects;
        };
        return create ? map.computeIfAbsent(id, k -> new Tally()) : map.get(id);
    }

    private static long effective(long base, Tally tally) {
        return base * (1L + tally.extensions.get());
    }

    /** The first limit {@code where} is over, or null. Changes nothing. */
    private Breach passed(Where where) {
        for (Level level : Level.values()) {
            Tally tally = tallyFor(level, where, false);
            Cap cap = limits.of(level);
            if (tally == null || !cap.any()) {
                continue;
            }
            for (Direction direction : Direction.values()) {
                long base = cap.of(direction);
                if (base > 0 && tally.of(direction) > effective(base, tally)) {
                    return new Breach(level, direction, where.projectId(), where.storyId(),
                        where.runId(), effective(base, tally), tally.spend());
                }
            }
        }
        return null;
    }

    private void signal(Breach breach) {
        if (breach.runId() != null) {
            lastBreach.put(breach.runId(), breach);
        }
        Tally tally = tallyFor(breach.level(),
            new Where(breach.projectId(), breach.storyId(), breach.runId()), false);
        int extensions = tally == null ? 0 : tally.extensions.get();
        String key = breach.level() + ":" + breach.scopeId() + ":" + extensions + ":" + breach.runId();
        if (signalled.add(key)) {
            log.error("Cloud {} budget passed ({} limit {}; {} input and {} output tokens used) "
                + "for run {} - parking BUDGET_EXTENSION decision", breach.level(),
                breach.direction(), breach.limit(), breach.used().input(), breach.used().output(),
                breach.runId());
            onBreach.accept(breach);
        }
    }

    // --- asking and extending -------------------------------------------------------------------

    /** The limit this work is over right now, if any. Changes nothing. */
    public Optional<Breach> standing(Where where) {
        return where == null ? Optional.empty() : Optional.ofNullable(passed(where));
    }

    /**
     * Like {@link #standing(Where)}, and when a limit is passed also records it as the one that
     * parked this run and raises its decision if that has not been done for this run yet. Called by
     * the workflow before it starts a stage, so a run that is driven again while still over a limit
     * (its story or project passed it through another run) parks on a question of its own.
     */
    public Optional<Breach> parkingBreach(Where where) {
        Breach breach = where == null ? null : passed(where);
        if (breach != null) {
            signal(breach);
        }
        return Optional.ofNullable(breach);
    }

    /**
     * Raises, by the same amount again, the limit that parked {@code runId}: its own, its story's
     * or its project's. This is what answering a {@code BUDGET_EXTENSION} decision with "extend"
     * calls; the run is then driven on again by {@code WorkflowEngine.advance(run)} like any other
     * parked run.
     *
     * @return what was raised, or empty when this run has no passed limit on record (the process
     *         restarted since, which also zeroed every tally)
     */
    public Optional<Extension> extendForRun(UUID runId) {
        Breach breach = runId == null ? null : lastBreach.remove(runId);
        if (breach == null) {
            return Optional.empty();
        }
        Tally tally = tallyFor(breach.level(),
            new Where(breach.projectId(), breach.storyId(), breach.runId()), true);
        int times = tally.extensions.incrementAndGet() + 1;
        log.info("Cloud {} budget of {} raised by the same amount again (now {} times the "
            + "configured limit)", breach.level(), breach.scopeId(), times);
        Cap base = limits.of(breach.level());
        return Optional.of(new Extension(breach.level(), breach.scopeId(),
            new Cap(base.total() * times, base.input() * times, base.output() * times)));
    }

    // --- reading --------------------------------------------------------------------------------

    /** Tokens counted for a run since the process started; zero for a run never seen. */
    public Spend spendOfRun(UUID runId) {
        Tally tally = runId == null ? null : runs.get(runId);
        return tally == null ? new Spend(0, 0) : tally.spend();
    }

    public Spend spendOfStory(UUID storyId) {
        Tally tally = storyId == null ? null : stories.get(storyId);
        return tally == null ? new Spend(0, 0) : tally.spend();
    }

    public Spend spendOfProject(UUID projectId) {
        Tally tally = projectId == null ? null : projects.get(projectId);
        return tally == null ? new Spend(0, 0) : tally.spend();
    }

    /** Tokens charged on threads that were not driving a run; limited by nothing. */
    public Spend outsideRuns() {
        return unscoped.spend();
    }

    /** The configured limits, before any extension. */
    public Limits limits() {
        return limits;
    }

    /**
     * One line for a run report: what this run spent on cloud models and the limits in force for
     * it, its story and its project.
     */
    public String spendLine(Where where) {
        if (where == null) {
            return "Cloud tokens: not attributed to a run";
        }
        Spend run = spendOfRun(where.runId());
        StringBuilder sb = new StringBuilder("Cloud tokens this run: input ")
            .append(run.input()).append(" / output ").append(run.output())
            .append("; limits in force: ");
        boolean any = false;
        for (Level level : Level.values()) {
            Cap cap = limits.of(level);
            Tally tally = tallyFor(level, where, false);
            long times = 1L + (tally == null ? 0 : tally.extensions.get());
            StringBuilder one = new StringBuilder();
            for (Direction direction : Direction.values()) {
                if (cap.of(direction) > 0) {
                    one.append(one.length() == 0 ? "" : ", ").append(direction.name().toLowerCase())
                        .append(' ').append(cap.of(direction) * times);
                }
            }
            if (one.length() > 0) {
                sb.append(any ? "; " : "").append(level.name().toLowerCase()).append(" (")
                    .append(one).append(')');
                any = true;
            }
        }
        if (!any) {
            sb.append("none");
        }
        return sb.toString();
    }

    /** Rough token estimate for text whose true usage is not reported (chars/4). */
    public static long estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }

    /** Everything counted, inside runs or not. */
    public long used() {
        return all.input.get() + all.output.get();
    }

    /** The old single cap, applied outside runs; {@code <= 0} means uncapped. */
    public long cap() {
        return unscopedCap;
    }

    public boolean exhausted() {
        return unscopedCap > 0 && used() > unscopedCap;
    }
}
