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
import com.swarmcoder.app.config.ConfigLoader;
import com.swarmcoder.app.config.RolesConfig;
import com.swarmcoder.app.config.SwarmConfig;
import com.swarmcoder.inference.ModelQuirks;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which roles of a live harness walk run on the operator's own configured model server (the
 * {@code roles:} of {@code config.yaml}) instead of the one local server named by
 * {@code -Dswarmcoder.live.*}. {@code -Dswarmcoder.e2e.rolesFromConfig=architect,planner}.
 *
 * <p>Each named role gets exactly the client the product builds for it
 * ({@code DependencyGraph.clientFor}: the config entry's own base URL, key, model name and
 * {@code resolvedQuirks()} - its shape, never the local server's). Every role not named stays on
 * the local server. The key lives only inside {@link Server}; nothing here prints, logs or
 * stringifies it, and the failure messages name the role, never the key.
 *
 * <p>Which config role each name uses is the product's own wiring:
 * <ul>
 *   <li>architect, testAuthor, designReviewer, judge: the config role of that name.</li>
 *   <li>librarian: {@code roles.utility}. The product builds its Librarian (the framework-primer
 *       model) on utility; the config key {@code roles.librarian} is not read by any wiring.</li>
 *   <li>analyst: {@code roles.requirementsAnalyst}, else {@code roles.chat}, else
 *       {@code roles.utility} (DependencyGraph.roleClientsFor; there is no analyst key).</li>
 *   <li>planner: {@code roles.storyPlanner}, else chat, else utility.</li>
 *   <li>expert: {@code roles.utility} when it names an endpoint and a model, else
 *       {@code roles.architect} (ProjectContext.expertRoleFor).</li>
 * </ul>
 * A project's own {@code project.yaml} overrides are not applied: the harness fixture has none.
 */
final class HarnessRoleServers {

    static final String PROPERTY = "swarmcoder.e2e.rolesFromConfig";

    /** A harness role: the flag's name and the tag the run meter puts on its calls. */
    enum Role {
        ARCHITECT("architect", "architect"),
        PLANNER("planner", "planner"),
        ANALYST("analyst", "analyst"),
        TEST_AUTHOR("testAuthor", "test author"),
        DESIGN_REVIEWER("designReviewer", "reviewer"),
        JUDGE("judge", "judge"),
        EXPERT("expert", "expert"),
        LIBRARIAN("librarian", "knowledge");

        final String flagName;
        final String meterTag;

        Role(String flagName, String meterTag) {
            this.flagName = flagName;
            this.meterTag = meterTag;
        }
    }

    /** One model server a role runs on. The key is never part of {@link #toString()}. */
    static final class Server {
        private final String baseUrl;
        private final String apiKey;
        private final String model;
        private final ModelQuirks quirks;
        private final boolean local;

        Server(String baseUrl, String apiKey, String model, ModelQuirks quirks, boolean local) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey == null ? "" : apiKey;
            this.model = model;
            this.quirks = quirks;
            this.local = local;
        }

        String baseUrl() {
            return baseUrl;
        }

        String apiKey() {
            return apiKey;
        }

        String model() {
            return model;
        }

        ModelQuirks quirks() {
            return quirks;
        }

        boolean local() {
            return local;
        }

        /** Host only - never the path or the key. */
        String host() {
            try {
                String host = URI.create(baseUrl).getHost();
                return host == null ? "(unknown host)" : host;
            } catch (RuntimeException e) {
                return "(unknown host)";
            }
        }

        String label() {
            return (local ? "local " : "config ") + host() + " / " + model;
        }

        /** The label and, when the quirks are known, the working room of one session. */
        String labelWithRoom() {
            return quirks == null ? label()
                : label() + " (working room " + quirks.workingContextTokens() + " tokens)";
        }

        @Override
        public String toString() {
            return label();
        }
    }

    /** The roles taken from the config, each with its server; empty when the flag is unset. */
    record Choice(Map<Role, Server> fromConfig) {

        static final Choice NONE = new Choice(Map.of());

        boolean cloud(Role role) {
            return fromConfig.containsKey(role);
        }

        /** The server {@code role} runs on: its config server, else {@code local}. */
        Server serverOf(Role role, Server local) {
            return fromConfig.getOrDefault(role, local);
        }

        /** What the report needs to tell a call to the local server from any other. */
        HarnessRunReport.Servers forReport(Server local) {
            Map<String, String> byRole = new LinkedHashMap<>();
            Map<String, String> byModel = new LinkedHashMap<>();
            fromConfig.forEach((role, server) -> {
                byRole.put(role.meterTag, server.label());
                if (!server.model().equals(local.model())) {
                    byModel.put(server.model(), server.label());
                }
            });
            return new HarnessRunReport.Servers(local.label(), byRole, byModel);
        }

        /** One line per role: which server host and model it runs on. Never a key. */
        List<String> startUpLines(Server local) {
            List<String> lines = new ArrayList<>();
            for (Role role : Role.values()) {
                lines.add("[E2E] role " + role.flagName + " runs on " + serverOf(role, local).labelWithRoom());
            }
            return lines;
        }
    }

    private HarnessRoleServers() {
    }

    /** The flag's roles in the order given; an unknown name fails, naming it and the valid ones. */
    static Set<Role> parse(String flag) {
        Set<Role> out = new LinkedHashSet<>();
        if (flag == null || flag.isBlank()) {
            return out;
        }
        for (String raw : flag.split(",")) {
            String name = raw.strip();
            if (name.isEmpty()) {
                continue;
            }
            Role found = null;
            for (Role role : Role.values()) {
                if (role.flagName.equalsIgnoreCase(name)) {
                    found = role;
                }
            }
            if (found == null) {
                throw new IllegalArgumentException("-D" + PROPERTY + " names an unknown role '"
                    + name + "'; the roles are "
                    + Arrays.stream(Role.values()).map(r -> r.flagName).toList());
            }
            out.add(found);
        }
        return out;
    }

    /**
     * The server each requested role runs on, from the product's own config roles.
     *
     * @throws IllegalStateException when a requested role has no settings (no base URL and model
     *                                name) anywhere in its chain; names the role only
     */
    static Choice resolve(Set<Role> requested, RolesConfig roles) {
        Map<Role, Server> out = new EnumMap<>(Role.class);
        for (Role role : requested) {
            AgentModelConfig entry = roles == null ? null : entryFor(role, roles);
            if (!named(entry)) {
                throw new IllegalStateException("-D" + PROPERTY + " asks for the role '"
                    + role.flagName + "' from config.yaml, but config.yaml has no settings (base "
                    + "URL and model name) for it" + chainNote(role) + ".");
            }
            out.put(role, new Server(entry.baseUrl(), entry.apiKey(), entry.modelName(),
                entry.resolvedQuirks(), false));
        }
        return new Choice(out);
    }

    /** Reads the operator's config the way the product does, without writing a starter file. */
    static Choice resolveFromFile(Set<Role> requested) {
        if (requested.isEmpty()) {
            return Choice.NONE;
        }
        Path path = ConfigLoader.configPath();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("-D" + PROPERTY + " names " + requested.stream()
                .map(r -> r.flagName).toList() + " but there is no config.yaml at the product's "
                + "config path to take them from");
        }
        SwarmConfig config;
        try {
            config = ConfigLoader.loadDefaultConfig();
        } catch (IOException | RuntimeException e) {
            // Not the exception's message: a parse error can quote the file.
            throw new IllegalStateException("-D" + PROPERTY + ": config.yaml could not be read ("
                + e.getClass().getSimpleName() + ")");
        }
        return resolve(requested, config == null ? null : config.roles());
    }

    /** The flag's value from the system property. */
    static Choice fromSystemProperty() {
        return resolveFromFile(parse(System.getProperty(PROPERTY)));
    }

    static AgentModelConfig entryFor(Role role, RolesConfig roles) {
        return switch (role) {
            case ARCHITECT -> roles.architect();
            case TEST_AUTHOR -> roles.testAuthor();
            case DESIGN_REVIEWER -> roles.designReviewer();
            case JUDGE -> roles.judge();
            case LIBRARIAN -> roles.utility();
            case ANALYST -> firstNamed(roles.requirementsAnalyst(), roles.chat(), roles.utility());
            case PLANNER -> firstNamed(roles.storyPlanner(), roles.chat(), roles.utility());
            case EXPERT -> firstNamed(roles.utility(), roles.architect());
        };
    }

    private static String chainNote(Role role) {
        return switch (role) {
            case ANALYST -> " (it uses requirementsAnalyst, then chat, then utility)";
            case PLANNER -> " (it uses storyPlanner, then chat, then utility)";
            case EXPERT -> " (it uses utility, then architect)";
            case LIBRARIAN -> " (the product builds the librarian on roles.utility; roles.librarian is not read)";
            default -> "";
        };
    }

    private static AgentModelConfig firstNamed(AgentModelConfig... chain) {
        for (AgentModelConfig candidate : chain) {
            if (named(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean named(AgentModelConfig role) {
        return role != null && role.baseUrl() != null && !role.baseUrl().isBlank()
            && role.modelName() != null && !role.modelName().isBlank();
    }

    /**
     * The scheduler pool of the local worker model when {@code endpoint} is the local server, else
     * null: a role on its own server takes no place on the workers' server (the product's
     * {@code ProjectContext.workerPoolOf} rule).
     */
    static String poolOf(String localBaseUrl, String localModel, String endpoint) {
        if (endpoint == null || localBaseUrl == null) {
            return null;
        }
        return root(endpoint).equalsIgnoreCase(root(localBaseUrl)) ? localModel : null;
    }

    private static String root(String url) {
        return url.replaceAll("/+$", "").replaceAll("/v1$", "");
    }
}
