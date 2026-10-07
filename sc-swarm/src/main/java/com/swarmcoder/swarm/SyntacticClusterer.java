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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.CandidateSolution;
import com.swarmcoder.domain.ClusterId;
import com.swarmcoder.syntax.SyntaxService;
import com.swarmcoder.syntax.Language;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SyntacticClusterer {

    private static final Logger log =
        LoggerFactory.getLogger(SyntacticClusterer.class);

    private final SyntaxService syntaxService;
    private final ProbeExecutor probeExecutor;

    public SyntacticClusterer(SyntaxService syntaxService, ProbeExecutor probeExecutor) {
        this.syntaxService = syntaxService;
        this.probeExecutor = probeExecutor;
    }

    public List<CandidateSolution> cluster(List<CandidateSolution> survived, Language lang) {
        Map<String, List<CandidateSolution>> clusters = new HashMap<>();
        
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (CandidateSolution sol : survived) {
                String normalized = syntaxService.normalizeForClustering(lang, sol.diffUnified());
                List<String> probeOutputs = probeExecutor.executeProbes(sol);
                String combinedForHash = normalized + "\n" + String.join("\n", probeOutputs);

                byte[] hashBytes = digest.digest(combinedForHash.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte b : hashBytes) {
                    hex.append(String.format("%02x", b));
                }
                String hash = hex.toString();
                clusters.computeIfAbsent(hash, k -> new ArrayList<>()).add(sol);
            }
        } catch (Exception e) {
            log.error("Clustering failed — candidates will pass through unclustered", e);
        }

        List<CandidateSolution> clustered = new ArrayList<>();
        for (Map.Entry<String, List<CandidateSolution>> entry : clusters.entrySet()) {
            ClusterId clusterId = new ClusterId(entry.getKey(), entry.getValue().size());
            for (CandidateSolution sol : entry.getValue()) {
                CandidateSolution updated = new CandidateSolution(sol.id(), sol.taskId(), sol.workerIndex(),
                    sol.branch(), sol.sampling(), sol.diffUnified(), sol.verification(), clusterId,
                    sol.judge(), sol.state(), sol.killReason()).carryingAuditFrom(sol);
                clustered.add(updated);
            }
        }
        return clustered;
    }
}
