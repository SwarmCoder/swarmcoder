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

import com.swarmcoder.inference.VllmClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision behind the help desk's expert tier in production (ExpertDesk javadoc): which role
 * backs it, and the fallback order. Pure logic, so it is proved here without building a project —
 * {@link DependencyGraphStartupTest#noExpertRoleConfiguredWarnsOnceAtStartup} proves the same
 * decision reaches the real wiring and logs correctly when nobody configured either role.
 */
class ProjectContextExpertWiringTest {

    private static final VllmClient ANY = new VllmClient("http://localhost:1", "", "m", true);

    @Test
    void utilityWinsWhenBothAreConfigured() {
        assertThat(ProjectContext.expertRoleFor(
            new RoleClients(ANY, ANY, ANY, ANY, ANY, ANY, ANY, ANY, true, true)))
            .isEqualTo("utility");
    }

    @Test
    void architectIsTheFallbackWhenUtilityIsNot() {
        assertThat(ProjectContext.expertRoleFor(
            new RoleClients(ANY, ANY, ANY, ANY, ANY, ANY, ANY, ANY, false, true)))
            .isEqualTo("architect");
    }

    @Test
    void utilityAloneIsEnough() {
        assertThat(ProjectContext.expertRoleFor(
            new RoleClients(ANY, ANY, ANY, ANY, ANY, ANY, ANY, ANY, true, false)))
            .isEqualTo("utility");
    }

    @Test
    void neitherConfiguredMeansNoExpert() {
        assertThat(ProjectContext.expertRoleFor(
            new RoleClients(ANY, ANY, ANY, ANY, ANY, ANY, ANY, ANY, false, false)))
            .as("null, not the default client's role — a role that only exists because clientFor() "
                + "falls back to it is not \"configured\"")
            .isNull();
    }
}
