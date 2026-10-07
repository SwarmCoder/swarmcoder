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

import com.swarmcoder.domain.LibraryDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a worker may declare, decided from a synthetic BOM and a fake local repository.
 *
 * <p>Both halves of the rule are exercised separately, because they fail for different reasons and
 * an operator has to be able to tell them apart: an artifact the BOM does not manage has no version
 * anyone could write down, and an artifact the repository does not hold cannot be fetched by a
 * sandbox that has no network. Only an artifact that passes both may be offered to a worker.
 */
class DeclarableArtifactsTest {

    @TempDir
    Path repo;
    @TempDir
    Path m2;

    /** The repository under test: a two-module reactor importing one BOM. */
    @BeforeEach
    void writeReactor() throws Exception {
        Files.writeString(repo.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>demo</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <properties>
                <stack.version>2.3.0</stack.version>
              </properties>
              <modules><module>server</module></modules>
              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>com.example.stack</groupId>
                    <artifactId>stack-bom</artifactId>
                    <version>${stack.version}</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>
                  <dependency>
                    <groupId>org.junit.jupiter</groupId>
                    <artifactId>junit-jupiter</artifactId>
                    <version>5.11.4</version>
                    <scope>test</scope>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
            """);
        Path server = Files.createDirectories(repo.resolve("server"));
        Files.writeString(server.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>demo</artifactId>
                <version>1.0.0</version>
              </parent>
              <artifactId>server</artifactId>
              <dependencies>
                <dependency>
                  <groupId>com.example.stack</groupId>
                  <artifactId>stack-server</artifactId>
                </dependency>
              </dependencies>
            </project>
            """);
    }

    @Test
    void anArtifactTheBomManagesAndTheRepositoryHoldsIsDeclarable() throws Exception {
        writeBom("2.3.0", "stack-server", "stack-store", "stack-client");
        installJar("com.example.stack", "stack-server", "2.3.0");
        installJar("com.example.stack", "stack-store", "2.3.0");
        installJar("com.example.stack", "stack-client", "2.3.0");

        DeclarableArtifacts.Catalog catalog =
            DeclarableArtifacts.scan(repo, List.of("server"), m2);

        assertThat(catalog.artifacts()).extracting(DeclarableArtifacts.Artifact::coordinate)
            .contains("com.example.stack:stack-store", "com.example.stack:stack-client");
        assertThat(catalog.byArtifactId("stack-store")).isPresent();
        assertThat(catalog.byArtifactId("stack-store").orElseThrow().version())
            .as("the version comes from the BOM, so nothing has to invent one")
            .isEqualTo("2.3.0");
        assertThat(catalog.byArtifactId("stack-store").orElseThrow().managedBy())
            .contains("stack-bom");
    }

    @Test
    void anArtifactTheRepositoryDoesNotHoldIsNotDeclarable() throws Exception {
        writeBom("2.3.0", "stack-server", "stack-store");
        installJar("com.example.stack", "stack-server", "2.3.0");
        // stack-store is managed by the BOM but was never installed.

        DeclarableArtifacts.Catalog catalog =
            DeclarableArtifacts.scan(repo, List.of("server"), m2);

        assertThat(catalog.byArtifactId("stack-store"))
            .as("a sandbox with no network cannot fetch it, so offering it would be a lie")
            .isEmpty();
        assertThat(catalog.byArtifactId("stack-server")).isPresent();
    }

    @Test
    void anArtifactNoBomManagesIsNotDeclarableEvenWhenItIsOnTheDisk() throws Exception {
        writeBom("2.3.0", "stack-server");
        installJar("com.example.stack", "stack-server", "2.3.0");
        installJar("org.apache.commons", "commons-lang3", "3.14.0");

        DeclarableArtifacts.Catalog catalog =
            DeclarableArtifacts.scan(repo, List.of("server"), m2);

        assertThat(catalog.byArtifactId("commons-lang3"))
            .as("nothing pins its version, so declaring it means inventing one")
            .isEmpty();
    }

    @Test
    void aVersionThePomExpressesAsAPropertyIsResolved() throws Exception {
        // The BOM is at ${stack.version} in the root pom; finding it at all proves the property
        // was resolved, and the entries inside it use ${project.version}.
        writeBom("2.3.0", "stack-store");
        installJar("com.example.stack", "stack-store", "2.3.0");

        DeclarableArtifacts.Catalog catalog =
            DeclarableArtifacts.scan(repo, List.of("server"), m2);

        assertThat(catalog.byArtifactId("stack-store").orElseThrow().version()).isEqualTo("2.3.0");
    }

    @Test
    void aMissingLocalRepositoryIsSaidRatherThanGuessed() {
        DeclarableArtifacts.Catalog catalog =
            DeclarableArtifacts.scan(repo, List.of("server"), m2.resolve("not-there"));

        assertThat(catalog.repositoryPresent()).isFalse();
        assertThat(catalog.isEmpty()).isTrue();
    }

    @Test
    void theWorkerIsOfferedTheGroupsItAlreadyBuildsWithAndAnythingTheRulesName() throws Exception {
        writeBom("2.3.0", "stack-server", "stack-store");
        installJar("com.example.stack", "stack-server", "2.3.0");
        installJar("com.example.stack", "stack-store", "2.3.0");
        installJar("org.junit.jupiter", "junit-jupiter", "5.11.4");

        DeclarableArtifacts.Catalog catalog =
            DeclarableArtifacts.scan(repo, List.of("server"), m2);
        OfflineLibraryBrief brief = new OfflineLibraryBrief(catalog,
            List.of(new LibraryDoc("com.example.stack:stack-server", "2.3.0", "")),
            "- Storage is an object graph\n  Persistence uses `stack-store`.\n");

        assertThat(brief.offered()).extracting(DeclarableArtifacts.Artifact::coordinate)
            .as("stack-server is already declared, so it belongs in the first list, not this one")
            .containsExactly("com.example.stack:stack-store");
        assertThat(brief.render())
            .contains("Libraries you may ADD")
            .contains("com.example.stack:stack-store")
            .contains("NO <version> element");
    }

    @Test
    void nothingToOfferRendersNothingAtAll() {
        OfflineLibraryBrief brief = new OfflineLibraryBrief(
            DeclarableArtifacts.Catalog.empty(m2), List.of(), "");
        assertThat(brief.render()).isEmpty();
    }

    // ------------------------------------------------------------------------------- fixtures

    /** A BOM in the fake local repository managing the named artifacts at its own version. */
    private void writeBom(String version, String... artifacts) throws Exception {
        StringBuilder managed = new StringBuilder();
        for (String artifact : artifacts) {
            managed.append("""
                      <dependency>
                        <groupId>com.example.stack</groupId>
                        <artifactId>%s</artifactId>
                        <version>${project.version}</version>
                      </dependency>
                """.formatted(artifact));
        }
        Path dir = Files.createDirectories(
            m2.resolve("com/example/stack/stack-bom").resolve(version));
        Files.writeString(dir.resolve("stack-bom-" + version + ".pom"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example.stack</groupId>
              <artifactId>stack-bom</artifactId>
              <version>%s</version>
              <packaging>pom</packaging>
              <dependencyManagement>
                <dependencies>
            %s    </dependencies>
              </dependencyManagement>
            </project>
            """.formatted(version, managed));
    }

    /** An artifact present on the disk the sandbox mounts: a pom and a jar, as Maven installs it. */
    private void installJar(String groupId, String artifactId, String version) throws Exception {
        Path dir = Files.createDirectories(
            m2.resolve(groupId.replace('.', '/')).resolve(artifactId).resolve(version));
        Files.writeString(dir.resolve(artifactId + "-" + version + ".pom"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
            </project>
            """.formatted(groupId, artifactId, version));
        Files.writeString(dir.resolve(artifactId + "-" + version + ".jar"), "not really a jar");
    }
}
