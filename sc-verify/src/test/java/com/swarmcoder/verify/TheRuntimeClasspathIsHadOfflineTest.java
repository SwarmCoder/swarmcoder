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
package com.swarmcoder.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A module whose build copies no dependencies is started with the classpath the dependency
 * plugin writes, named at a version the offline repository holds; with none there, nothing is
 * guessed (DEVELOPER_CORRECTIONS section 108). And the port an application opened itself is
 * read from the kernel's table. Nothing is built or run; only this test's {@code @TempDir}.
 */
class TheRuntimeClasspathIsHadOfflineTest {

    @TempDir
    Path tree;

    @TempDir
    Path repository;

    private static final List<ServeFromTheCode.EntryPoint> ONE_MAIN = List.of(
        new ServeFromTheCode.EntryPoint("src/main/java/com/app/Main.java", "com.app.Main", false));

    @Test
    void withNoCopiedDependenciesTheDependencyPluginTheRepositoryHoldsWritesTheClasspath()
            throws Exception {
        Files.writeString(tree.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");
        for (String version : List.of("3.6.1", "3.10.0", "3.9.0")) {
            Path dir = Files.createDirectories(repository.resolve(
                "org/apache/maven/plugins/maven-dependency-plugin/" + version));
            Files.writeString(dir.resolve("maven-dependency-plugin-" + version + ".jar"), "");
        }
        // A version with a pom and no jar cannot run, so it is not named.
        Files.createDirectories(repository.resolve(
            "org/apache/maven/plugins/maven-dependency-plugin/3.11.0"));

        ServeFromTheCode.Outcome outcome = ServeFromTheCode.serve(tree, ONE_MAIN,
            BuildLayout.read(tree, "maven"), null, null, repository);

        assertThat(outcome.question()).isNull();
        assertThat(outcome.browser().serve())
            .startsWith("mvn -o -q -B -Dmaven.test.skip=true package "
                + "org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath")
            .contains("-Dmdep.includeScope=runtime")
            .contains("-cp \"target/classes:$(cat target/swarmcoder-runtime-classpath.txt)\" "
                + "com.app.Main")
            .doesNotContain("cd ");
    }

    @Test
    void withNeitherTheRunIsAskedAndNothingIsGuessed() throws Exception {
        Files.writeString(tree.resolve("pom.xml"), "<project><artifactId>app</artifactId></project>");

        ServeFromTheCode.Outcome outcome = ServeFromTheCode.serve(tree, ONE_MAIN,
            BuildLayout.read(tree, "maven"), null, null, repository);

        assertThat(outcome.browser()).isNull();
        assertThat(outcome.question()).contains("com.app.Main")
            .contains(ServeFromTheCode.DEPENDENCY_PLUGIN);
    }

    @Test
    void thePortsSomethingListensOnAreReadFromTheKernelsTable() {
        String table = """
              sl  local_address rem_address   st tx_queue rx_queue
               0: 00000000:BAEF 00000000:0000 0A 00000000:00000000 00:00000000 00000000
               1: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000
               2: 0100007F:1F90 0100007F:D431 01 00000000:00000000 00:00000000 00000000
              sl  local_address                         remote_address                        st
               0: 00000000000000000000000000000000:46A0 00000000000000000000000000000000:0000 0A
            """;

        assertThat(BrowserVerifier.listeningIn(table)).containsExactly(8080, 18080, 47855);
        assertThat(BrowserVerifier.onPort("http://127.0.0.1:18080/ready?x=1", 8080))
            .isEqualTo("http://127.0.0.1:8080/ready?x=1");
    }
}
