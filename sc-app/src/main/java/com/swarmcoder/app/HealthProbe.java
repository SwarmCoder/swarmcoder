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

import com.swarmcoder.console.ConsoleContext;
import com.swarmcoder.inference.AdaptiveConcurrency;
import com.swarmcoder.inference.EndpointReachability;
import com.swarmcoder.runtime.CloudGate;

import java.util.function.Supplier;

/**
 * Console health probe (design §8): the sc-app-side implementation of {@link ConsoleContext.Health}
 * where the sandbox flag and {@link CloudGate} live.
 *
 * <p><b>Reachability is delegated, not reimplemented.</b> It used to be a private {@code GET /v1/models}
 * here, which meant the health dot and the swarm's own decision about whether to dispatch were two
 * separate answers to one question. A dot reading "up" beside a build paused for an outage is worse than
 * either being wrong alone, because nothing tells the operator which to believe. {@link
 * EndpointReachability} is now the single derivation (UX v3 rule 2) and carries the TTL cache, so status
 * polling still never hammers the endpoint.
 */
final class HealthProbe implements ConsoleContext.Health {

    private final CloudGate cloudGate;
    private final boolean dockerEnabled;
    private final Supplier<String> workerBaseUrl;
    private final EndpointReachability reachability;

    HealthProbe(CloudGate cloudGate, boolean dockerEnabled, Supplier<String> workerBaseUrl) {
        this(cloudGate, dockerEnabled, workerBaseUrl, new EndpointReachability());
    }

    HealthProbe(CloudGate cloudGate, boolean dockerEnabled, Supplier<String> workerBaseUrl,
                EndpointReachability reachability) {
        this.cloudGate = cloudGate;
        this.dockerEnabled = dockerEnabled;
        this.workerBaseUrl = workerBaseUrl;
        this.reachability = reachability;
    }

    /** UNKNOWN reaches the operator as "unknown", never as "down": they mean different things. */
    @Override
    public String sparkStatus() {
        return switch (probe().status()) {
            case UP -> "up";
            case DOWN -> "down";
            case UNKNOWN -> "unknown";
        };
    }

    private EndpointReachability.Probe probe() {
        return reachability.probe(workerBaseUrl.get());
    }

    @Override
    public String sparkUrl() {
        return probe().endpoint();
    }

    @Override
    public String sparkModels() {
        return probe().models();
    }

    /**
     * Why the status is what it is.
     *
     * <p>The "nothing configured" case names the config key here rather than in
     * {@link EndpointReachability}, which serves the engine too and has no business knowing what a
     * {@code roles.workerFamilies} is. Generic where it is shared, specific where it is read — and
     * "unknown" without the key to go and set is a status an operator can do nothing with.
     */
    @Override
    public String sparkDetail() {
        EndpointReachability.Probe probe = probe();
        if (probe.status() == EndpointReachability.Status.UNKNOWN && probe.endpoint() == null) {
            return "No worker endpoint is configured — roles.workerFamilies has no baseUrl, so there "
                + "is nothing to probe.";
        }
        // When the server is running fewer workers than it started with, the health strip is the
        // place the operator already looks at the server, so the sentence rides here. A silent
        // drop from eight workers to two reads as the system dying; this is what says otherwise.
        java.util.List<String> throttled = AdaptiveConcurrency.shared().throttled();
        if (throttled.isEmpty()) {
            return probe.detail();
        }
        String detail = probe.detail() == null ? "" : probe.detail().strip();
        return (detail.isEmpty() ? "" : detail + (detail.endsWith(".") ? " " : ". "))
            + String.join(" ", throttled);
    }


    @Override
    public String dockerStatus() {
        return dockerEnabled ? "on" : "off";
    }

    @Override
    public long budgetUsed() {
        return cloudGate.used();
    }

    @Override
    public long budgetMax() {
        return cloudGate.cap();
    }
}
