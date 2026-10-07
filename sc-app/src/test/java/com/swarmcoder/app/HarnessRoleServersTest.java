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

import com.swarmcoder.app.config.AgentModelConfig;
import com.swarmcoder.app.config.RolesConfig;
import com.swarmcoder.inference.RunMeter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The harness flag that takes some roles from the operator's config. No server is called. */
class HarnessRoleServersTest {

    private static final String FAKE_KEY = "test-key-not-real";
    private static final String LOCAL_URL = "http://192.168.0.10:8000/v1";

    private static AgentModelConfig cfg(String host, String model, String shape) {
        return new AgentModelConfig("openai", "https://" + host + "/v1", FAKE_KEY, model, null,
            shape, null);
    }

    private static RolesConfig roles(AgentModelConfig architect, AgentModelConfig utility,
                                     AgentModelConfig chat, AgentModelConfig analyst,
                                     AgentModelConfig planner) {
        return new RolesConfig(architect, null, null, null, null, null, null, utility, chat,
            analyst, planner, null);
    }

    private static Set<HarnessRoleServers.Role> set(String flag) {
        return HarnessRoleServers.parse(flag);
    }

    @Test
    void theFlagIsParsedInOrderAndIgnoresCaseAndBlanks() {
        assertThat(set(" Architect, planner ,,TESTAUTHOR"))
            .containsExactly(HarnessRoleServers.Role.ARCHITECT, HarnessRoleServers.Role.PLANNER,
                HarnessRoleServers.Role.TEST_AUTHOR);
        assertThat(set(null)).isEmpty();
        assertThat(set("  ")).isEmpty();
        assertThat(set("architect,planner,analyst,testAuthor,designReviewer,judge,expert,librarian"))
            .hasSize(8);
    }

    @Test
    void anUnknownRoleNameFailsAndNamesIt() {
        assertThatThrownBy(() -> set("architect,approver"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("approver")
            .hasMessageContaining("designReviewer");
    }

    @Test
    void eachNameTakesTheConfigRoleTheProductUses() {
        AgentModelConfig architect = cfg("arch.example", "arch-model", null);
        AgentModelConfig utility = cfg("util.example", "util-model", null);
        AgentModelConfig planner = cfg("plan.example", "plan-model", null);
        HarnessRoleServers.Choice choice = HarnessRoleServers.resolve(
            set("architect,planner,analyst,expert,librarian"),
            roles(architect, utility, null, null, planner));
        Map<HarnessRoleServers.Role, HarnessRoleServers.Server> got = choice.fromConfig();
        assertThat(got.get(HarnessRoleServers.Role.ARCHITECT).model()).isEqualTo("arch-model");
        // storyPlanner when set; analyst has no key of its own and falls through chat to utility.
        assertThat(got.get(HarnessRoleServers.Role.PLANNER).model()).isEqualTo("plan-model");
        assertThat(got.get(HarnessRoleServers.Role.ANALYST).model()).isEqualTo("util-model");
        // the expert escalates to utility before architect; the librarian is built on utility.
        assertThat(got.get(HarnessRoleServers.Role.EXPERT).model()).isEqualTo("util-model");
        assertThat(got.get(HarnessRoleServers.Role.LIBRARIAN).model()).isEqualTo("util-model");
    }

    @Test
    void theWizardRolesFallBackThroughChatBeforeUtilityAndTheExpertFallsBackToTheArchitect() {
        AgentModelConfig chat = cfg("chat.example", "chat-model", null);
        AgentModelConfig utility = cfg("util.example", "util-model", null);
        AgentModelConfig architect = cfg("arch.example", "arch-model", null);
        HarnessRoleServers.Choice withChat = HarnessRoleServers.resolve(set("planner,analyst"),
            roles(null, utility, chat, null, null));
        assertThat(withChat.fromConfig().get(HarnessRoleServers.Role.PLANNER).model())
            .isEqualTo("chat-model");
        assertThat(withChat.fromConfig().get(HarnessRoleServers.Role.ANALYST).model())
            .isEqualTo("chat-model");
        HarnessRoleServers.Choice noUtility = HarnessRoleServers.resolve(set("expert"),
            roles(architect, null, null, null, null));
        assertThat(noUtility.fromConfig().get(HarnessRoleServers.Role.EXPERT).model())
            .isEqualTo("arch-model");
    }

    @Test
    void aNamedRoleWithNoSettingsFailsNamingTheRoleAndNeverTheKey() {
        // judge is not configured at all; the architect entry has no model name.
        RolesConfig roles = roles(new AgentModelConfig("openai", "https://x.example/v1", FAKE_KEY,
            "", null), null, null, null, null);
        assertThatThrownBy(() -> HarnessRoleServers.resolve(set("judge"), roles))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("'judge'")
            .hasMessageNotContaining(FAKE_KEY);
        assertThatThrownBy(() -> HarnessRoleServers.resolve(set("architect"), roles))
            .hasMessageContaining("'architect'").hasMessageNotContaining(FAKE_KEY);
        assertThatThrownBy(() -> HarnessRoleServers.resolve(set("planner"), roles))
            .hasMessageContaining("'planner'").hasMessageContaining("storyPlanner")
            .hasMessageNotContaining(FAKE_KEY);
        assertThatThrownBy(() -> HarnessRoleServers.resolve(set("judge"), null))
            .hasMessageContaining("'judge'");
    }

    @Test
    void aCloudRoleGetsItsOwnShapeNotTheLocalServers() {
        AgentModelConfig plain = cfg("cloud.example", "cloud-model", null);
        AgentModelConfig shaped = cfg("cloud.example", "cloud-model", "deepseek-v4-flash-ds4");
        var plainQuirks = HarnessRoleServers.resolve(set("architect"),
            roles(plain, null, null, null, null)).fromConfig()
            .get(HarnessRoleServers.Role.ARCHITECT).quirks();
        var shapedQuirks = HarnessRoleServers.resolve(set("architect"),
            roles(shaped, null, null, null, null)).fromConfig()
            .get(HarnessRoleServers.Role.ARCHITECT).quirks();
        // exactly what the product resolves for that config entry, nothing from -Dswarmcoder.live.shape
        System.setProperty("swarmcoder.live.shape", "deepseek-v4-flash-ds4");
        try {
            var stillPlain = HarnessRoleServers.resolve(set("architect"),
                roles(plain, null, null, null, null)).fromConfig()
                .get(HarnessRoleServers.Role.ARCHITECT).quirks();
            assertThat(stillPlain).isEqualTo(plain.resolvedQuirks()).isEqualTo(plainQuirks);
        } finally {
            System.clearProperty("swarmcoder.live.shape");
        }
        assertThat(shapedQuirks).isEqualTo(shaped.resolvedQuirks());
    }

    @Test
    void aServerNeverPrintsItsKeyAndTheStartUpLinesNameHostAndModelOnly() {
        HarnessRoleServers.Choice choice = HarnessRoleServers.resolve(set("architect,planner"),
            roles(cfg("cloud.example", "cloud-model", null), cfg("u.example", "u-model", null),
                null, null, null));
        HarnessRoleServers.Server local =
            new HarnessRoleServers.Server(LOCAL_URL, "", "local-model", null, true);
        List<String> lines = choice.startUpLines(local);
        assertThat(lines).hasSize(HarnessRoleServers.Role.values().length);
        assertThat(String.join("\n", lines)).doesNotContain(FAKE_KEY).doesNotContain("/v1")
            .contains("role architect runs on config cloud.example / cloud-model")
            .contains("role planner runs on config u.example / u-model")
            .contains("role judge runs on local 192.168.0.10 / local-model");
        HarnessRoleServers.Server withRoom = new HarnessRoleServers.Server(LOCAL_URL, "", "m",
            com.swarmcoder.inference.ModelShapes.get("deepseek-v4-flash-api"), true);
        assertThat(withRoom.labelWithRoom()).endsWith("(working room 983040 tokens)");
        assertThat(choice.fromConfig().get(HarnessRoleServers.Role.ARCHITECT).toString())
            .doesNotContain(FAKE_KEY);
    }

    @Test
    void theConfigIsReadFromTheProductsOwnPathAndAMissingFileWritesNothing(@TempDir Path home)
            throws Exception {
        String before = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            assertThatThrownBy(() -> HarnessRoleServers.resolveFromFile(set("architect")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("architect");
            assertThat(home.resolve(".swarmcoder").resolve("config.yaml")).doesNotExist();

            Path dir = Files.createDirectories(home.resolve(".swarmcoder"));
            Files.writeString(dir.resolve("config.yaml"), """
                roles:
                  architect:
                    protocol: openai
                    baseUrl: https://cloud.example/v1
                    apiKey: %s
                    modelName: cloud-model
                """.formatted(FAKE_KEY));
            HarnessRoleServers.Choice choice = HarnessRoleServers.resolveFromFile(
                set("architect"));
            assertThat(choice.cloud(HarnessRoleServers.Role.ARCHITECT)).isTrue();
            assertThat(choice.cloud(HarnessRoleServers.Role.JUDGE)).isFalse();
            assertThat(choice.fromConfig().get(HarnessRoleServers.Role.ARCHITECT).host())
                .isEqualTo("cloud.example");
            assertThatThrownBy(() -> HarnessRoleServers.resolveFromFile(set("architect,judge")))
                .hasMessageContaining("'judge'").hasMessageNotContaining(FAKE_KEY);
            assertThat(HarnessRoleServers.resolveFromFile(new LinkedHashSet<>()).fromConfig())
                .isEmpty();

            Files.writeString(dir.resolve("config.yaml"), "roles: [unclosed " + FAKE_KEY);
            assertThatThrownBy(() -> HarnessRoleServers.resolveFromFile(set("architect")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(FAKE_KEY);
        } finally {
            System.setProperty("user.home", before);
        }
    }

    @Test
    void onlyTheLocalServersEndpointTakesAPlaceOnTheLocalScheduler() {
        assertThat(HarnessRoleServers.poolOf(LOCAL_URL, "local-model", LOCAL_URL + "/"))
            .isEqualTo("local-model");
        assertThat(HarnessRoleServers.poolOf(LOCAL_URL, "local-model",
            "http://192.168.0.10:8000")).isEqualTo("local-model");
        assertThat(HarnessRoleServers.poolOf(LOCAL_URL, "local-model",
            "https://cloud.example/v1")).isNull();
        assertThat(HarnessRoleServers.poolOf(LOCAL_URL, "local-model", null)).isNull();
    }

    @Test
    void theReportSplitsTheServersAndMeasuresOnlyTheLocalOneInSectionSeven() {
        long t0 = 1_000_000_000_000L;
        RunMeter.Call cloudArchitect = new RunMeter.Call("architect", null, null, "cloud-model",
            t0, t0 + 600_000, 5000, 90_000);
        RunMeter.Call localWorker = new RunMeter.Call("worker", null, "0", "local-model",
            t0 + 10_000, t0 + 70_000, 1000, 1200);
        HarnessRunReport.Servers servers = new HarnessRunReport.Servers("local 192.168.0.10 / "
            + "local-model", Map.of("architect", "config cloud.example / cloud-model"),
            Map.of("cloud-model", "config cloud.example / cloud-model"));
        String report = HarnessRunReport.render(new HarnessRunReport.Input(
            List.of(cloudArchitect, localWorker), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), t0, t0 + 700_000, null, "n/a",
            null, "done", null, null, servers));

        assertThat(report)
            .contains("| role | server | calls |")
            .contains("| architect | config cloud.example / cloud-model | 1 | 5000 | 5000 | 90000 |")
            .contains("| worker | local 192.168.0.10 / local-model | 1 | 1000 | 1000 | 1200 |")
            .contains("### Calls to other servers")
            .contains("| config cloud.example / cloud-model | 1 | 5000 | 90000 |");
        // The 600 s cloud call must not make the local peak 2 nor stretch the local rate:
        // the local worker alone writes 1200 tokens in 60 s = 20 tokens/s.
        String sectionSeven = report.substring(report.indexOf("## 7."),
            report.indexOf("### Calls to other servers"));
        assertThat(sectionSeven).contains("| whole run |").contains("| 20.0 |")
            .doesNotContain("150.0");
    }

    @Test
    void anAllLocalRunKeepsTheOldReportTable() {
        String report = HarnessRunReport.render(new HarnessRunReport.Input(List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), 0, 1000, null, "n/a", null, "done", null));
        assertThat(report).contains("| role | calls |").doesNotContain("| server |")
            .doesNotContain("Calls to other servers");
    }

    /**
     * Harness run 77: with "expert" taken from config and the same model name on both servers,
     * "expert for architect" and "expert for planner" were reported as local (the role tag did not
     * match "expert", and the model name told nothing).
     */
    @Test
    void anExpertSessionOpenedForAPlanningRoleRunsOnTheExpertServer() {
        String cloud = "config cloud.example / same-model";
        HarnessRunReport.Servers servers = new HarnessRunReport.Servers(
            "local 192.168.0.10 / same-model", Map.of("expert", cloud), Map.of());
        for (String role : List.of("expert", "expert for architect", "expert for planner")) {
            RunMeter.Call call = new RunMeter.Call(role, null, null, "same-model", 0, 1000, 10, 10);
            assertThat(servers.labelOf(call)).as(role).isEqualTo(cloud);
            assertThat(servers.isLocal(call)).as(role).isFalse();
        }
        RunMeter.Call worker = new RunMeter.Call("worker", null, "0", "same-model", 0, 1000, 10, 10);
        assertThat(servers.isLocal(worker)).isTrue();
    }
}
