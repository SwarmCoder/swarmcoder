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

import com.swarmcoder.domain.Decision;
import com.swarmcoder.domain.DecisionKind;
import com.swarmcoder.domain.DecisionState;
import com.swarmcoder.runtime.TraceHub;
import com.swarmcoder.store.ArtifactStore;
import com.zeroz4j.server.ClientVisibleException;
import com.zeroz4j.server.RmiRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A watch-only console refuses what a browser asks it to do to a run, and lets everything else
 * through (2026-09-25).
 *
 * <p>The end-to-end harness serves this Console over its own store while it drives a run through
 * the same services the browser calls. So the refusal cannot be "these methods are off": the
 * harness's own {@code startSession} has to start its story. It is "these methods are off when a
 * browser asks", and a browser is recognised by the WebSocket session ZeroZ's RMI dispatcher puts on
 * the calling thread for the length of each call. These tests put that session on the thread the
 * way the dispatcher does, and take it off again.
 */
class ConsoleWatchOnlyTest {

    @TempDir
    Path dir;

    @AfterEach
    void clear() {
        RmiRequestContext.clear();
        ConsoleContext.set(null);
    }

    @Test
    void aBrowserCannotStartApproveRejectOrCreateOnAWatchOnlyConsole() {
        AtomicInteger acted = new AtomicInteger();
        ConsoleContext context = new ConsoleContext(null, TraceHub.NONE,
            (goal, kind) -> { acted.incrementAndGet(); return UUID.randomUUID(); },
            runId -> acted.incrementAndGet(), runId -> acted.incrementAndGet())
            .withProjects(List::of, () -> null,
                (name, path, ctx) -> { acted.incrementAndGet(); return null; }, id -> { })
            .watchOnly("the harness is driving this run");

        asTheBrowser();
        assertThatThrownBy(() -> context.startRun("g", "GREENFIELD"))
            .isInstanceOf(ClientVisibleException.class)
            .hasMessage("You cannot start a build here: the harness is driving this run.");
        assertThatThrownBy(() -> context.startRun("g", "GREENFIELD", UUID.randomUUID()))
            .isInstanceOf(ClientVisibleException.class);
        assertThatThrownBy(() -> context.approveRun(UUID.randomUUID()))
            .isInstanceOf(ClientVisibleException.class);
        assertThatThrownBy(() -> context.rejectRun(UUID.randomUUID()))
            .isInstanceOf(ClientVisibleException.class);
        assertThatThrownBy(() -> context.createProject("p", "/tmp/p", List.of()))
            .isInstanceOf(ClientVisibleException.class);
        assertThat(acted).describedAs("nothing reached the wiring").hasValue(0);
    }

    @Test
    void theHarnessItselfIsNotRefused() {
        AtomicInteger started = new AtomicInteger();
        ConsoleContext context = new ConsoleContext(null, TraceHub.NONE,
            (goal, kind) -> { started.incrementAndGet(); return UUID.randomUUID(); },
            runId -> { }, runId -> { })
            .watchOnly("the harness is driving this run");

        // No browser session on this thread: this is the harness's own call.
        context.startRun("g", "GREENFIELD");
        context.startRun("g", "GREENFIELD", UUID.randomUUID());
        context.approveRun(UUID.randomUUID());

        assertThat(started).hasValue(2);
    }

    @Test
    void anOrdinaryConsoleRefusesTheBrowserNothing() {
        AtomicInteger started = new AtomicInteger();
        ConsoleContext context = new ConsoleContext(null, TraceHub.NONE,
            (goal, kind) -> { started.incrementAndGet(); return UUID.randomUUID(); },
            runId -> { }, runId -> { });

        asTheBrowser();
        context.startRun("g", "GREENFIELD");

        assertThat(context.watchOnlyReason()).isNull();
        assertThat(started).hasValue(1);
    }

    /** Answering goes straight to the store, past every seam, so it is refused in the service. */
    @Test
    void aBrowserCannotAnswerTheRunsQuestionOnAWatchOnlyConsole() throws Exception {
        try (ArtifactStore store = new ArtifactStore(dir)) {
            UUID decisionId = UUID.randomUUID();
            store.append(() -> {
                store.root().decisions.put(decisionId, new Decision(decisionId, UUID.randomUUID(),
                    DecisionKind.BLOCKED_TASK, "which way?", DecisionState.PENDING, null,
                    Instant.now()));
                return null;
            }).get();
            ConsoleContext.set(new ConsoleContext(store, TraceHub.NONE, (g, k) -> null,
                runId -> { }, runId -> { }).watchOnly("the harness is driving this run"));
            ControlServiceImpl control = new ControlServiceImpl();

            asTheBrowser();
            assertThatThrownBy(() -> control.resolveDecision(decisionId.toString(), "left"))
                .isInstanceOf(ClientVisibleException.class)
                .hasMessageContaining("You cannot answer a question here");
            assertThatThrownBy(() -> control.resolveAllPendingDecisions("left"))
                .isInstanceOf(ClientVisibleException.class);
            assertThatThrownBy(control::startAutonomousBuild)
                .isInstanceOf(ClientVisibleException.class);
            // The wizards are model work on the one model server the harness's workers are using.
            String anyFlow = UUID.randomUUID().toString();
            assertThatThrownBy(() -> new GuidedFlowServiceImpl().start(anyFlow))
                .isInstanceOf(ClientVisibleException.class);
            assertThatThrownBy(() -> new GuidedFlowServiceImpl().submitAnswers(anyFlow))
                .isInstanceOf(ClientVisibleException.class);
            assertThatThrownBy(() -> new PlanningFlowServiceImpl().start(anyFlow))
                .isInstanceOf(ClientVisibleException.class);

            assertThat(store.root().decisions.get(decisionId).state())
                .describedAs("the question the harness reads as 'the run parked' is still open")
                .isEqualTo(DecisionState.PENDING);
        }
    }

    /** What ZeroZ's dispatcher does on its thread before it calls a service method. */
    private static void asTheBrowser() {
        RmiRequestContext.setContext(null, Set.of("admin"), "websocket-session-1");
    }
}
