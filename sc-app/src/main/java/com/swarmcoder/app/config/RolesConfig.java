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
package com.swarmcoder.app.config;

import java.util.List;

public record RolesConfig(
    AgentModelConfig architect,
    AgentModelConfig testAuthor,
    AgentModelConfig librarian,
    AgentModelConfig designReviewer,
    AgentModelConfig judge,
    AgentModelConfig approver,
    List<AgentModelConfig> workerFamilies,
    AgentModelConfig utility,
    /** The Console chat coder (CONSOLE_DESIGN_V2.md §5.2); falls back to utility when unset. */
    AgentModelConfig chat,
    /**
     * The BRD author: reads the uploaded documents and constructs the requirement graph — DRAFT
     * requirements with their checks, relationships and source refs
     * (REQUIREMENTS_AND_BACKLOG_DESIGN.md §4.1, §5.1). Falls back to {@link #chat}.
     *
     * <p>It had no slot of its own until now for one historical reason: it was reachable as the
     * {@code /brd} chat mode, so it used the chat model. When {@code /brd} became the "Analyse
     * documents" wizard (GUIDED_FLOWS_DESIGN.md G2) the wizard inherited the chat model with it.
     * That put the top of the requirement chain — no swarm behind it, no test in front of it — in a
     * slot sized for conversation, while the merely mechanical half of intake ({@link #vision}) had
     * its own. Configuring this separately is the point: it is a frontier-grade judgment role.
     */
    AgentModelConfig requirementsAnalyst,
    /**
     * The story planner: slices the agreed requirements' unclaimed checks into stories
     * (REQUIREMENTS_AND_BACKLOG_DESIGN.md §5.2). Falls back to {@link #chat}, and shares the
     * {@link #requirementsAnalyst} history — it was the {@code /backlog} chat mode.
     */
    AgentModelConfig storyPlanner,
    /**
     * Vision-capable model used to read requirements out of uploaded images (intake, see
     * docs/REQUIREMENTS_AND_BACKLOG_DESIGN.md §4.3). Deliberately has NO fallback: the text roles
     * are not vision-capable, so silently substituting one would produce a confident description of
     * an image the model never saw. Unset means image upload is refused with an explicit message.
     */
    AgentModelConfig vision
) {}
