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
package com.swarmcoder.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.swarmcoder.inference.MaterialBudget;
import com.swarmcoder.knowledge.ExpertTools;
import com.swarmcoder.knowledge.KnowledgeCurator;
import com.swarmcoder.runtime.CloudGate;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Run 86 (DEVELOPER_CORRECTIONS section 58): the lookups address a file of the project as
 * {@code project/<path>} when reference material is loaded beside it, the test author gave its
 * draft to {@code compile_test} at that address, and it was refused as "not under" the directory
 * it was under. No model is called.
 */
class ADraftIsAddressedTheWayTheLookupsAddressFilesTest {

    @TempDir
    Path world;

    @Test
    void aDraftGivenWithTheProjectsLabelIsCompiledAtItsRealPath() {
        KnowledgeCurator curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", world, "local")), null,
            world.resolve("cache"));
        ExpertTools session = new ExpertTools(curator, null, null, new CloudGate(0, null),
            MaterialBudget.forWorkingContext(983_040), 0,
            new ExpertTools.User("test author", "hand in", 0, null, 0, 0));
        List<String> compiled = new ArrayList<>();
        TestAuthorTools tools = new TestAuthorTools(session, "shop-server/src/test/java/swarm",
            "shop-server/src/test/java/swarm/accept", (Map<String, String> files) -> {
                compiled.addAll(files.keySet());
                return new TestAuthorTools.Verdict(true, "HEALTHY");
            });

        String answer = tools.compileTest(
            "project/shop-server/src/test/java/swarm/accept/BasketTest.java", "class BasketTest {}");

        assertThat(answer).contains("HEALTHY").doesNotContain("Refused");
        assertThat(compiled).containsExactly("shop-server/src/test/java/swarm/accept/BasketTest.java");
        assertThat(tools.submission())
            .containsOnlyKeys("shop-server/src/test/java/swarm/accept/BasketTest.java");
        assertThat(tools.compileTest("project/shop-server/src/main/java/Other.java", "class Other {}"))
            .as("outside the acceptance directory is refused as before").startsWith("Refused");
    }
}
