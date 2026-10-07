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
package com.swarmcoder.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 85 (2026-10-04): the architect asked {@code build_of ''} to see the project's three
 * modules and was sent 12,711 characters - a line for every build file of the framework checkout
 * and its examples - which every later call sent again.
 */
class EveryBuildFileMeansTheProjectsOwnTest {

    @TempDir
    Path world;

    @Test
    void askedForEverythingAReferenceCheckoutIsCountedAndTheProjectIsListed() throws Exception {
        Path app = world.resolve("app");
        Path framework = world.resolve("framework");
        pom(app.resolve("pom.xml"), "shop");
        pom(app.resolve("shop-server/pom.xml"), "shop-server");
        for (String module : List.of("core", "store", "examples/chat", "examples/forms")) {
            pom(framework.resolve(module + "/pom.xml"), module.replace('/', '-'));
            Files.createDirectories(framework.resolve(module + "/src/main/java/f"));
            Files.writeString(framework.resolve(module + "/src/main/java/f/A"
                + Math.abs(module.hashCode()) + ".java"),
                "package f;\nclass A" + Math.abs(module.hashCode()) + " {}\n");
        }
        pom(framework.resolve("pom.xml"), "framework");
        KnowledgeCurator curator = new KnowledgeCurator(List.of(
            new KnowledgeCurator.Root("project", app, "local"),
            new KnowledgeCurator.Root("framework", framework, "1")), null, world.resolve("cache"));
        TreeQueries tree = new TreeQueries(curator);

        String everything = tree.buildOf("");

        assertThat(everything).contains("project/pom.xml")
            .contains("framework: ").contains("build file(s) of reference material")
            .contains("build_of framework/<module>")
            .doesNotContain("framework/core/pom.xml").doesNotContain("framework/store/pom.xml");
        assertThat(tree.buildOf("framework/core")).as("one of them is still one question away")
            .contains("framework/core/pom.xml").contains("artifact: f:core:1");
    }

    private static void pom(Path file, String artifact) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<project><groupId>f</groupId><artifactId>" + artifact
            + "</artifactId><version>1</version><dependencies><dependency><groupId>org.x"
            + "</groupId><artifactId>lib</artifactId><version>2</version></dependency>"
            + "</dependencies></project>");
    }
}
