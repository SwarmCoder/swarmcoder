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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.AcceptanceCriterion;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.verify.BrowserOnlyCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 70, after live run 95: a journey about a screen was claimed by the task whose test
 * author wrote it - a task that writes server code only - and the screen was written by another
 * task. When the journey failed in the browser, the repair went to workers who may not touch
 * the screen. Which task OWNS a journey is settled from the write sets and the build, with no
 * model and nothing read from a title or the journey's words.
 */
class AJourneyIsOwnedByTheTaskThatWritesTheScreenTest {

    private static final String DIR = "shop-server/src/test/java/swarm";
    private static final String JOURNEY = DIR + "/accept/find-order.journey.yaml";

    private static final BrowserOnlyCode.Survey SURVEY = new BrowserOnlyCode.Survey(
        List.of(new BrowserOnlyCode.Module("shop-client", "declares a browser runtime",
            "compiled to JavaScript", List.of("com.shop.client"))),
        List.of("shop-shared", "shop-server"), Map.of());

    private final Task service = task("Order service",
        Set.of("shop-server/src/main/java/com/shop/server/OrderServiceImpl.java"), true);
    private final Task shell = task("Application shell",
        Set.of("shop-client/src/main/java/com/shop/client/Shell.java"), false);
    private final Task screen = task("Orders screen",
        Set.of("shop-client/src/main/java/com/shop/client/screen/OrdersScreen.java",
            "shop-client/pom.xml"), false);

    /** Run 95's shape: the only task that answers for a check writes the server. */
    @Test
    void aJourneyWrittenWithAServerTasksTestsIsOwnedByTheTaskThatWritesTheScreen() {
        service.setJourneyPaths(List.of(JOURNEY));

        List<JourneysOfAPlan.Moved> moved =
            JourneysOfAPlan.settleOwners(List.of(service, screen), SURVEY, true);

        assertThat(moved).hasSize(1);
        assertThat(moved.get(0).path()).isEqualTo(JOURNEY);
        assertThat(moved.get(0).from()).isSameAs(service);
        assertThat(moved.get(0).to()).isSameAs(screen);
        assertThat(service.journeyPaths()).as("a server-only task may be why a journey is "
            + "needed; it does not own it").isEmpty();
        assertThat(screen.journeyPaths()).containsExactly(JOURNEY);
    }

    @Test
    void ofSeveralTasksThatWriteBrowserCodeTheLastInThePlansOrderOwnsIt() {
        service.setJourneyPaths(List.of(JOURNEY));

        JourneysOfAPlan.settleOwners(List.of(shell, service, screen), SURVEY, true);

        assertThat(screen.journeyPaths()).containsExactly(JOURNEY);
        assertThat(shell.journeyPaths()).isEmpty();
        assertThat(service.journeyPaths()).isEmpty();

        // The order is the plan's, not the list of write sets': the shell built last owns it.
        Task laterShell = task("Application shell",
            Set.of("shop-client/src/main/java/com/shop/client/Shell.java"), false);
        Task earlierScreen = task("Orders screen",
            Set.of("shop-client/src/main/java/com/shop/client/screen/OrdersScreen.java"), false);
        Task server = task("Order service",
            Set.of("shop-server/src/main/java/com/shop/server/OrderServiceImpl.java"), true);
        server.setJourneyPaths(List.of(JOURNEY));

        JourneysOfAPlan.settleOwners(List.of(earlierScreen, server, laterShell), SURVEY, true);

        assertThat(laterShell.journeyPaths()).containsExactly(JOURNEY);
        assertThat(earlierScreen.journeyPaths()).isEmpty();
    }

    @Test
    void aJourneyWrittenWithATaskThatWritesAScreenStaysWithIt() {
        Task ownScreen = task("Customers screen",
            Set.of("shop-client/src/main/java/com/shop/client/screen/CustomersScreen.java"), true);
        ownScreen.setJourneyPaths(List.of(JOURNEY));

        assertThat(JourneysOfAPlan.settleOwners(List.of(ownScreen, screen), SURVEY, true))
            .as("written for that task's screen: the later screen task does not take it")
            .isEmpty();
        assertThat(ownScreen.journeyPaths()).containsExactly(JOURNEY);
        assertThat(screen.journeyPaths()).isEmpty();
    }

    /** Section 64's shape: the screen is already there and draws what the server returns. */
    @Test
    void whenNoTaskWritesAScreenTheJourneyStaysWithTheTaskItWasWrittenWith() {
        service.setJourneyPaths(List.of(JOURNEY));

        assertThat(JourneysOfAPlan.settleOwners(List.of(service), SURVEY, true)).isEmpty();
        assertThat(service.journeyPaths()).containsExactly(JOURNEY);
    }

    @Test
    void aPageFileCountsAsAScreenOnlyWhenTheContractStartsTheApplication() {
        Task page = task("Orders page", Set.of("web/orders.html"), false);
        service.setJourneyPaths(List.of(JOURNEY));

        assertThat(JourneysOfAPlan.settleOwners(List.of(service, page),
            BrowserOnlyCode.Survey.NONE, false)).isEmpty();
        assertThat(JourneysOfAPlan.settleOwners(List.of(service, page),
            BrowserOnlyCode.Survey.NONE, true)).hasSize(1);
        assertThat(page.journeyPaths()).containsExactly(JOURNEY);
    }

    /**
     * A plan made before this rule, resumed from a saved stage: settling it again changes
     * nothing more, and a claim both tasks hold (the tests were written again) ends on one.
     */
    @Test
    void settlingAgainChangesNothingAndAClaimHeldTwiceEndsOnTheOwnerAlone() {
        service.setJourneyPaths(List.of(JOURNEY));
        JourneysOfAPlan.settleOwners(List.of(service, screen), SURVEY, true);

        assertThat(JourneysOfAPlan.settleOwners(List.of(service, screen), SURVEY, true))
            .isEmpty();

        service.setJourneyPaths(List.of(JOURNEY)); // its author wrote the journey again
        List<JourneysOfAPlan.Moved> again =
            JourneysOfAPlan.settleOwners(List.of(service, screen), SURVEY, true);

        assertThat(again).hasSize(1);
        assertThat(service.journeyPaths()).isEmpty();
        assertThat(screen.journeyPaths()).containsExactly(JOURNEY);
    }

    private static Task task(String title, Set<String> writeSet, boolean withACheck) {
        return new Task(UUID.randomUUID(), 1, title, title, writeSet, Set.of(),
            withACheck ? List.of(new AcceptanceCriterion(UUID.randomUUID(),
                "an order can be found", "swarm.accept.OrdersTest#findsAnOrder")) : List.of(),
            DIR, null, null, new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }
}
