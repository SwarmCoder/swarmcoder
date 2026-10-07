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

import com.swarmcoder.console.api.HealthDto;
import com.swarmcoder.console.api.HealthService;
import com.swarmcoder.domain.Run;
import com.swarmcoder.domain.RunState;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class HealthServiceImpl implements HealthService {

    @Override
    public HealthDto snapshot() {
        ConsoleContext ctx = ConsoleContext.get();
        var store = ctx.store();
        HealthDto dto = new HealthDto();

        dto.setActiveWorkers(ctx.traceHub().activeSessions().size());

        int active = 0;
        int total = 0;
        for (Run run : store.root().runs.values()) {
            total++;
            RunState state = run.state();
            if (state != RunState.DELIVERED && state != RunState.ABORTED
                    && state != RunState.ABANDONED) {
                active++;
            }
        }
        dto.setActiveRuns(active);
        dto.setTotalRuns(total);
        dto.setSessions(store.root().agentSessions().size());

        // Via PendingDecisions rather than an inline loop: the Build stage counts the same thing,
        // and two hand-rolled PENDING filters over one map is how two surfaces come to disagree
        // about how much the operator owes. Global, like every other counter on this strip.
        dto.setPendingApprovals(PendingDecisions.all(store));

        ConsoleContext.Health health = ctx.health();
        dto.setSparkStatus(health.sparkStatus());
        dto.setSparkUrl(health.sparkUrl());
        dto.setSparkModels(health.sparkModels());
        dto.setSparkDetail(health.sparkDetail());
        dto.setDockerStatus(health.dockerStatus());
        dto.setBudgetUsed(health.budgetUsed());
        dto.setBudgetMax(health.budgetMax());
        return dto;
    }
}
