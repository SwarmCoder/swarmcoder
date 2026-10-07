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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single source of model identity (spec §6.2, correction S1). Populated from config at
 * wiring time; code asks for profiles by id or role, never constructs endpoints ad hoc.
 */
public final class ModelProfileRegistry {

    private final Map<String, ModelProfile> byId = new LinkedHashMap<>();

    public ModelProfileRegistry(List<ModelProfile> profiles) {
        for (ModelProfile profile : profiles) {
            if (byId.putIfAbsent(profile.id(), profile) != null) {
                throw new IllegalArgumentException("Duplicate model profile id: " + profile.id());
            }
        }
    }

    public ModelProfile require(String id) {
        ModelProfile profile = byId.get(id);
        if (profile == null) {
            throw new IllegalArgumentException("No model profile '" + id + "' configured; known: " + byId.keySet());
        }
        return profile;
    }

    public ModelProfile find(String id) {
        return byId.get(id);
    }

    /** Worker families in config order — the swarm's diversity axis (spec §3.3). */
    public List<ModelProfile> workers() {
        return byId.values().stream().filter(p -> p.role() == ModelProfile.Kind.WORKER).toList();
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }
}
