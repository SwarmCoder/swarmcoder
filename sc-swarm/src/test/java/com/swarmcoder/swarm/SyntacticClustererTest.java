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
import com.swarmcoder.domain.SamplingConfig;
import com.swarmcoder.domain.CandidateState;
import com.swarmcoder.domain.ClusterId;
import com.swarmcoder.syntax.Language;
import com.swarmcoder.syntax.SyntaxService;
import com.swarmcoder.syntax.ParseVerdict;
import com.swarmcoder.syntax.RepoMap;
import com.swarmcoder.syntax.SymbolSig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Assertions;

public class SyntacticClustererTest {

    static class MockSyntaxService implements SyntaxService {
        @Override
        public ParseVerdict parse(Language lang, byte[] source) {
            return null;
        }

        @Override
        public RepoMap repoMap(Path repoRoot, Set<String> includeGlobs) {
            return null;
        }

        @Override
        public List<SymbolSig> signatures(Path file) {
            return null;
        }

        @Override
        public String normalizeForClustering(Language lang, String diffHunkContext) {
            // Very simple mock: ignore whitespace
            return diffHunkContext.replaceAll("\\s+", "");
        }
    }

    @Test
    public void testClusteringIdenticalHunks() {
        SyntaxService syntaxService = new MockSyntaxService();
        SyntacticClusterer clusterer = new SyntacticClusterer(syntaxService, c -> List.of("probe1", "probe2"));

        SamplingConfig config = new SamplingConfig("profile", 1.0, 1L, "persona", "full");

        CandidateSolution c1 = new CandidateSolution(UUID.randomUUID(), UUID.randomUUID(), 0, "branch",
                config, "int x = 1;", null, null, null, CandidateState.SURVIVED, null);
        CandidateSolution c2 = new CandidateSolution(UUID.randomUUID(), UUID.randomUUID(), 1, "branch",
                config, "int  x  =  1;", null, null, null, CandidateState.SURVIVED, null);
        CandidateSolution c3 = new CandidateSolution(UUID.randomUUID(), UUID.randomUUID(), 2, "branch",
                config, "int y = 2;", null, null, null, CandidateState.SURVIVED, null);

        List<CandidateSolution> clustered = clusterer.cluster(Arrays.asList(c1, c2, c3), Language.JAVA);

        assertEquals(3, clustered.size());

        CandidateSolution grouped1 = clustered.stream().filter(c -> c.workerIndex() == 0).findFirst().get();
        CandidateSolution grouped2 = clustered.stream().filter(c -> c.workerIndex() == 1).findFirst().get();
        CandidateSolution single = clustered.stream().filter(c -> c.workerIndex() == 2).findFirst().get();

        String hash1 = grouped1.cluster().behavioralHash();
        String hash2 = grouped2.cluster().behavioralHash();
        String hash3 = single.cluster().behavioralHash();

        assertEquals(hash1, hash2);
        assertEquals(2, grouped1.cluster().clusterSize());
        assertEquals(2, grouped2.cluster().clusterSize());

        // c3 is different
        Assertions.assertNotEquals(hash1, hash3);
        assertEquals(1, single.cluster().clusterSize());
    }
}
