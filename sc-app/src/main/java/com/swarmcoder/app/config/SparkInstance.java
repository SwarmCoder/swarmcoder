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

import com.fasterxml.jackson.annotation.JsonProperty;
/**
 * A configured vLLM instance ({@code spark.instances}).
 *
 * <p><b>Superseded for the figures below.</b> {@code contextCeiling},
 * {@code kvBytesPerTokenEstimate} and {@code maxNumSeqs} now live on each model's own profile
 * ({@code ModelQuirks}), where they are edited in Settings and keyed by the PROFILE id that
 * admission control actually leases against. Pools registered from this block are keyed by
 * {@code id}, so a model whose profile id did not happen to match an instance id was silently
 * admitted against the generic default pool. This block is kept so existing configs keep working;
 * per-model pools are registered after it and take precedence when the ids collide.
 */
public record SparkInstance(String id, String baseUrl, String servedModelName, 
    String toolDialect, int contextCeiling, int kvBytesPerTokenEstimate, int maxNumSeqs) {}
