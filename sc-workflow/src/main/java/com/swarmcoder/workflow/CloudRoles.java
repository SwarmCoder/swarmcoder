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

import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.runtime.CloudGate;

/**
 * The cloud-role clients the workflow front half uses (spec §14). Each role can point at a
 * different endpoint/family (de-correlation, spec §6.2); all share the one CloudGate.
 */
public record CloudRoles(
    ArchitectClient architect,
    DesignReviewerClient reviewer,
    TestAuthorClient testAuthor
) {
    /** All roles on one endpoint — the M1/M3 starting point and the test configuration. */
    public static CloudRoles allOn(VllmClient client, CloudGate cloudGate) {
        return new CloudRoles(
            new ArchitectClient(client, cloudGate),
            new DesignReviewerClient(client, cloudGate),
            new TestAuthorClient(client, cloudGate));
    }
}
