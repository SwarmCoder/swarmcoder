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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The detector must offer a browser block to a project that plainly serves a web application.
 *
 * <p>Before this, none of the five toolchains could produce one and {@link ToolchainDetector#render}
 * had no branch that would have written one out — so the only stage that starts the application and
 * looks at it was reachable by nobody, and it was reached by nobody: no {@code verify.yaml} in this
 * project carried a browser block. Every candidate ever verified was judged on compiling and
 * passing unit tests, while every acceptance criterion the operator writes is about an application
 * that runs.
 *
 * <p>The fixture is shaped like the real demo repository in this checkout: a multi-module Maven
 * reactor whose server module keeps its front page under {@code src/main/resources/META-INF/resources},
 * declares an embedded HTTP server, copies its dependencies into {@code target/libs}, and starts on a
 * port its {@code main} method hardcodes.
 */
class BrowserBlockIsProposedTest {

    @TempDir
    Path repo;

    @Test
    void aWebApplicationIsOfferedABrowserBlockThatRoundTrips() throws Exception {
        writeWebAppRepo();

        ToolchainDetector.Detection detection = ToolchainDetector.detect(repo);

        assertThat(detection.recognised()).isTrue();
        VerifySpec.BrowserSpec browser = detection.proposed().browser();
        assertThat(browser).as("a project that serves a web page must be offered a browser block")
            .isNotNull();

        // A serve command that actually builds what it then runs. `mvn compile test-compile` — the
        // compile stage of this contract — never reaches the package phase, so target/libs would not
        // exist when the browser stage ran.
        assertThat(browser.serve()).contains("package").contains("java -cp")
            .contains("com.example.demo.ServerApp");
        assertThat(browser.readyProbe()).isEqualTo("http://localhost:{PORT}/");
        // The port is read out of the source, because this application takes no port argument and
        // the {PORT} placeholder would otherwise be a lie.
        assertThat(browser.port()).isEqualTo(8080);
        assertThat(browser.checks()).hasSize(1);
        assertThat(browser.checks().get(0).url()).isEqualTo("/");
        assertThat(browser.checks().get(0).assertNoConsoleErrors()).isTrue();
        assertThat(browser.checks().get(0).screenshot()).isTrue();

        // The operator has to be told that a fixed port cannot be shared.
        assertThat(String.join("\n", detection.warnings()))
            .contains("hardcodes it").contains("8080");

        // The rendered contract must read back as the same block: a proposal the loader cannot
        // parse is a proposal that silently switches the stage off again.
        String yaml = ToolchainDetector.render(detection);
        assertThat(yaml).contains("browser:").contains("port: 8080").contains("readyProbe:");
        VerifySpec reloaded = VerifySpecLoader.parse(yaml);
        assertThat(reloaded.browser()).isEqualTo(browser);
    }

    @Test
    void aLibraryWithNoWebPageIsOfferedNothingAndToldSo() throws Exception {
        Files.writeString(repo.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId><artifactId>lib</artifactId><version>1.0</version>
              <dependencies><dependency><groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter</artifactId></dependency></dependencies>
            </project>
            """);
        Files.createDirectories(repo.resolve("src/main/java/com/example"));
        Files.writeString(repo.resolve("src/main/java/com/example/Adder.java"),
            "package com.example; public class Adder { public int add(int a, int b) { return a + b; } }");

        ToolchainDetector.Detection detection = ToolchainDetector.detect(repo);

        // Not a web application: no block, and no invented warning about one either.
        assertThat(detection.proposed().browser()).isNull();
        String yaml = ToolchainDetector.render(detection);
        assertThat(yaml).contains("# browser: nothing proposed");
        assertThat(VerifySpecLoader.parse(yaml).browser()).isNull();
    }

    /** A repository shaped like the bookshelf demo: reactor, web root, embedded server, main class. */
    private void writeWebAppRepo() throws IOException {
        Files.writeString(repo.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId><artifactId>demo</artifactId><version>1.0</version>
              <packaging>pom</packaging>
              <modules><module>demo-server</module></modules>
            </project>
            """);
        Path server = repo.resolve("demo-server");
        Files.createDirectories(server);
        Files.writeString(server.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion>
              <parent><groupId>com.example</groupId><artifactId>demo</artifactId>
                <version>1.0</version></parent>
              <artifactId>demo-server</artifactId>
              <dependencies>
                <dependency><groupId>io.helidon.microprofile.bundles</groupId>
                  <artifactId>helidon-microprofile</artifactId><version>4.0.0</version></dependency>
                <dependency><groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
              </dependencies>
              <build><plugins><plugin>
                <artifactId>maven-dependency-plugin</artifactId>
                <executions><execution><id>copy-libs</id><phase>package</phase>
                  <goals><goal>copy-dependencies</goal></goals>
                  <configuration><outputDirectory>${project.build.directory}/libs</outputDirectory>
                  </configuration></execution></executions>
              </plugin></plugins></build>
            </project>
            """);
        Path webRoot = server.resolve("src/main/resources/META-INF/resources");
        Files.createDirectories(webRoot);
        Files.writeString(webRoot.resolve("index.html"),
            "<!doctype html><html><body><div id=\"app-root\">Loading&hellip;</div></body></html>");
        Path main = server.resolve("src/main/java/com/example/demo");
        Files.createDirectories(main);
        Files.writeString(main.resolve("ServerApp.java"), """
            package com.example.demo;

            public class ServerApp {
                public static void main(String[] args) throws Exception {
                    DemoServer.start(8080, "Demo").join();
                }
            }
            """);
    }
}
