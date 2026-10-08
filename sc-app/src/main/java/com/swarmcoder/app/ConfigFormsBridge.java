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
import com.swarmcoder.app.config.BudgetsConfig;
import com.swarmcoder.app.config.ConfigLoader;
import com.swarmcoder.app.config.ProjectConfig;
import com.swarmcoder.app.config.ProjectConfigLoader;
import com.swarmcoder.app.config.ProjectSwarmConfig;
import com.swarmcoder.app.config.ModelQuirksConfig;
import com.swarmcoder.app.config.RolesConfig;
import com.swarmcoder.inference.ModelShapes;
import com.swarmcoder.console.api.BudgetsDto;
import com.swarmcoder.console.api.RoleEntryDto;
import com.swarmcoder.domain.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.swarmcoder.console.ConsoleContext;

/**
 * The Console's typed settings forms (CONSOLE_DESIGN_V2.md §7) over the real config files:
 * global roles/budgets round-trip through config.yaml (apply on restart, like the raw YAML
 * editor), project entries through each project's .swarmcoder/project.yaml with the
 * project's engine context invalidated so the next use picks the new models/folders up.
 */
final class ConfigFormsBridge implements ConsoleContext.ConfigForms {

    private static final Logger log = LoggerFactory.getLogger(ConfigFormsBridge.class);
    // Order is the order the form renders in, so it reads as the pipeline: intake first, then the
    // build roles, then the shared small ones. RoleForm labels rows from the id alone and adds
    // nothing per role, so a new entry here is a new editable row with no UI change.
    private static final List<String> NAMED_ROLES = List.of(
        "vision", "requirementsAnalyst", "storyPlanner",
        "architect", "taskPlanner", "designReviewer", "testAuthor", "judge", "approver",
        "librarian", "utility", "chat");

    private final DependencyGraph graph;

    ConfigFormsBridge(DependencyGraph graph) {
        this.graph = graph;
    }

    // --- global ----------------------------------------------------------------------------------

    @Override
    public List<RoleEntryDto> globalRoles() {
        return toEntries(graph.config.roles());
    }

    @Override
    public String saveGlobalRoles(List<RoleEntryDto> roles) {
        try {
            var updated = graph.config.withRoles(toRolesConfig(roles));
            ConfigLoader.saveConfig(updated);
            graph.config = updated;
            return "";
        } catch (Exception e) {
            log.warn("saveGlobalRoles failed: {}", e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    @Override
    public BudgetsDto budgets() {
        BudgetsDto dto = new BudgetsDto();
        BudgetsConfig budgets = graph.config.budgets();
        if (budgets != null) {
            dto.setMaxCloudTokensPerRun(budgets.maxCloudTokensPerRun());
            dto.setMaxLocalTokensPerTask(budgets.maxLocalTokensPerTask());
            dto.setWallClockCeilingHours(budgets.wallClockCeilingHours());
            dto.setMaxToolTurnsPerWorker(budgets.maxToolTurnsPerWorker());
        }
        return dto;
    }

    @Override
    public String saveBudgets(BudgetsDto budgets) {
        try {
            var updated = graph.config.withBudgets(new BudgetsConfig(
                budgets.getMaxCloudTokensPerRun(), budgets.getMaxLocalTokensPerTask(),
                budgets.getWallClockCeilingHours(), budgets.getMaxToolTurnsPerWorker()));
            ConfigLoader.saveConfig(updated);
            graph.config = updated;
            return "";
        } catch (Exception e) {
            log.warn("saveBudgets failed: {}", e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    // --- per project -----------------------------------------------------------------------------

    @Override
    public List<RoleEntryDto> projectRoles(String projectId) {
        Project project = graph.artifactStore.getProject(UUID.fromString(projectId));
        if (project == null) {
            return List.of();
        }
        ProjectConfig config = ProjectConfigLoader.load(project.primaryPath());
        return toEntries(config == null ? null : config.roles());
    }

    @Override
    public int projectWorkersPerTask(String projectId) {
        Project project = graph.artifactStore.getProject(UUID.fromString(projectId));
        if (project == null) {
            return 0;
        }
        ProjectConfig config = ProjectConfigLoader.load(project.primaryPath());
        Integer stated = config == null ? null : config.statedWorkersPerTask();
        return stated == null ? 0 : stated;   // 0 = this project inherits the global number
    }

    @Override
    public String saveProjectConfig(String projectId, String contextPathsCsv, List<RoleEntryDto> roles,
                                    int workersPerTask) {
        try {
            Project project = graph.artifactStore.getProject(UUID.fromString(projectId));
            if (project == null) {
                return "error: no such project " + projectId;
            }
            List<String> contextPaths = new ArrayList<>();
            if (contextPathsCsv != null && !contextPathsCsv.isBlank()) {
                for (String path : contextPathsCsv.split(",")) {
                    if (!path.strip().isEmpty()) {
                        contextPaths.add(path.strip());
                    }
                }
            }
            // protectedPaths is carried over from the file: this form does not edit locked
            // modules, and rewriting project.yaml without them would silently UNLOCK them.
            ProjectConfig existing = ProjectConfigLoader.load(project.primaryPath());
            // 0 means "inherit", and writing no swarm block at all is how that is said. A block
            // holding the inherited number would look, in project.yaml, like a decision somebody
            // made — and the run log would then name this project as the layer that won.
            ProjectSwarmConfig swarm = workersPerTask >= 1
                ? new ProjectSwarmConfig(workersPerTask) : null;
            ProjectConfigLoader.save(project.primaryPath(),
                new ProjectConfig(project.name(), contextPaths,
                    existing == null ? null : existing.protectedPaths(), toRolesConfig(roles),
                    swarm));
            project.setContextPaths(contextPaths);
            graph.artifactStore.saveProject(project);
            graph.invalidateProject(project.id()); // next use rebuilds with the new settings
            return "";
        } catch (Exception e) {
            log.warn("saveProjectConfig failed: {}", e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    // --- mapping ---------------------------------------------------------------------------------

    /** Named roles in fixed order, then worker families as worker0..n. Null-safe throughout. */
    private static List<RoleEntryDto> toEntries(RolesConfig roles) {
        List<RoleEntryDto> entries = new ArrayList<>();
        Map<String, AgentModelConfig> named = new HashMap<>();
        if (roles != null) {
            named.put("architect", roles.architect());
            named.put("designReviewer", roles.designReviewer());
            named.put("testAuthor", roles.testAuthor());
            named.put("judge", roles.judge());
            named.put("approver", roles.approver());
            named.put("librarian", roles.librarian());
            named.put("utility", roles.utility());
            named.put("chat", roles.chat());
            named.put("vision", roles.vision());
            named.put("requirementsAnalyst", roles.requirementsAnalyst());
            named.put("storyPlanner", roles.storyPlanner());
            named.put("taskPlanner", roles.taskPlanner());
        }
        for (String roleId : NAMED_ROLES) {
            entries.add(entry(roleId, named.get(roleId)));
        }
        List<AgentModelConfig> workers = roles == null || roles.workerFamilies() == null
            ? List.of() : roles.workerFamilies();
        for (int i = 0; i < workers.size(); i++) {
            entries.add(entry("worker" + i, workers.get(i)));
        }
        return entries;
    }

    private static RoleEntryDto entry(String roleId, AgentModelConfig config) {
        RoleEntryDto dto = new RoleEntryDto();
        dto.setRoleId(roleId);
        dto.setBaseUrl(config == null || config.baseUrl() == null ? "" : config.baseUrl());
        dto.setApiKey(config == null || config.apiKey() == null ? "" : config.apiKey());
        dto.setModelName(config == null || config.modelName() == null ? "" : config.modelName());
        dto.setThinking(config == null || config.thinking() == null ? -1
            : config.thinking() ? 1 : 0);
        // The shape list travels on the row so the settings screen never has to hold a list of
        // model names of its own — the catalogue is the one place shapes are named.
        dto.setAvailableShapes(String.join(",", ModelShapes.ids()));
        dto.setShape(config == null || config.shape() == null ? "" : config.shape());
        ModelQuirksConfig q = config == null ? null : config.quirks();
        if (q != null) {
            dto.setMaxOutputTokens(q.maxOutputTokens() == null ? 0 : q.maxOutputTokens());
            dto.setThinkingKwarg(q.thinkingKwarg() == null ? "" : q.thinkingKwarg());
            dto.setNoThinkDirective(q.noThinkDirective() == null ? "" : q.noThinkDirective());
            dto.setTextualToolHistory(tri(q.textualToolHistory()));
            dto.setJsonResponseFormat(tri(q.jsonResponseFormat()));
            dto.setHttpVersion(q.httpVersion() == null ? "" : q.httpVersion());
            dto.setServedContextTokens(q.servedContextTokens() == null ? 0 : q.servedContextTokens());
            dto.setWorkingContextTokens(q.workingContextTokens() == null ? 0 : q.workingContextTokens());
            dto.setKvBytesPerToken(q.kvBytesPerToken() == null ? 0 : q.kvBytesPerToken());
            dto.setMaxConcurrentSequences(q.maxConcurrentSequences() == null ? 0 : q.maxConcurrentSequences());
            dto.setVerified(tri(q.verified()));
        } else {
            dto.setThinkingKwarg("");
            dto.setNoThinkDirective("");
            dto.setHttpVersion("");
        }
        return dto;
    }

    private static int tri(Boolean value) {
        return value == null ? -1 : value ? 1 : 0;
    }

    private static Boolean unTri(int value) {
        return value < 0 ? null : value == 1;
    }

    private static RolesConfig toRolesConfig(List<RoleEntryDto> entries) {
        Map<String, AgentModelConfig> named = new HashMap<>();
        List<AgentModelConfig> workers = new ArrayList<>();
        for (RoleEntryDto entry : entries == null ? List.<RoleEntryDto>of() : entries) {
            AgentModelConfig config = fromEntry(entry);
            if (entry.getRoleId() != null && entry.getRoleId().startsWith("worker")) {
                if (config != null) {
                    workers.add(config);
                }
            } else {
                named.put(entry.getRoleId(), config);
            }
        }
        return new RolesConfig(named.get("architect"), named.get("testAuthor"),
            named.get("librarian"), named.get("designReviewer"), named.get("judge"),
            named.get("approver"), workers, named.get("utility"), named.get("chat"),
            named.get("requirementsAnalyst"), named.get("storyPlanner"),
            named.get("vision"), named.get("taskPlanner"));
    }

    /** An entry with no endpoint at all means "unset/inherit" — stored as null. */
    private static AgentModelConfig fromEntry(RoleEntryDto entry) {
        boolean empty = isBlank(entry.getBaseUrl()) && isBlank(entry.getModelName())
            && isBlank(entry.getApiKey());
        if (empty) {
            return null;
        }
        // A quirks block is written only when the operator actually stated something. An
        // all-null block would look, in config.yaml, like a set of decisions nobody made.
        ModelQuirksConfig quirks = new ModelQuirksConfig(
            entry.getMaxOutputTokens() > 0 ? entry.getMaxOutputTokens() : null,
            nullIfBlank(entry.getThinkingKwarg()),
            nullIfBlank(entry.getNoThinkDirective()),
            unTri(entry.getTextualToolHistory()),
            unTri(entry.getJsonResponseFormat()),
            nullIfBlank(entry.getHttpVersion()),
            entry.getServedContextTokens() > 0 ? entry.getServedContextTokens() : null,
            entry.getWorkingContextTokens() > 0 ? entry.getWorkingContextTokens() : null,
            entry.getKvBytesPerToken() > 0 ? entry.getKvBytesPerToken() : null,
            entry.getMaxConcurrentSequences() > 0 ? entry.getMaxConcurrentSequences() : null,
            unTri(entry.getVerified()));
        boolean statedNothing = quirks.equals(new ModelQuirksConfig(
            null, null, null, null, null, null, null, null, null, null, null));
        return new AgentModelConfig("OpenAI", nullIfBlank(entry.getBaseUrl()),
            nullIfBlank(entry.getApiKey()), nullIfBlank(entry.getModelName()),
            entry.getThinking() < 0 ? null : entry.getThinking() == 1,
            nullIfBlank(entry.getShape()),
            statedNothing ? null : quirks);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullIfBlank(String s) {
        return isBlank(s) ? null : s;
    }
}
