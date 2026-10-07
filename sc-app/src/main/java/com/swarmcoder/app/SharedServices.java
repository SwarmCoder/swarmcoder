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

import com.swarmcoder.inference.AdaptiveConcurrency;
import com.swarmcoder.inference.InferenceScheduler;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.Context7Client;
import com.swarmcoder.knowledge.DocsIndex;
import com.swarmcoder.knowledge.HistoryRag;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.CloudGate;
import com.swarmcoder.runtime.ModelProfileRegistry;
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.store.ArtifactStore;
import com.swarmcoder.verify.BlobSink;
import com.swarmcoder.knowledge.McpServers;

/**
 * Process-wide singletons shared across every {@link ProjectContext}: one EclipseStore, one
 * inference scheduler, one Koog runtime, one Docker sandbox pool, etc. A project layers its own
 * git/librarian/guidelines/engine on top of these (see {@link ProjectContext}).
 */
public record SharedServices(
    ArtifactStore store,
    InferenceScheduler scheduler,
    AgentRuntime runtime,
    ModelProfileRegistry profiles,
    CloudGate cloudGate,
    BlobSink blobSink,
    VllmClient defaultClient,
    DockerSandboxManager sandbox,
    HistoryRag historyRag,
    Context7Client context7,
    DocsIndex docsIndex,
    /** User-configured MCP servers ({@code mcpServers} config) offered to the Researcher. */
    McpServers mcpServers,
    /**
     * Fewer workers on a model server whose workers keep running out of room, each with more of
     * it. Process-wide like the scheduler, because a server's pool is shared by every project.
     */
    AdaptiveConcurrency concurrency
) {}
