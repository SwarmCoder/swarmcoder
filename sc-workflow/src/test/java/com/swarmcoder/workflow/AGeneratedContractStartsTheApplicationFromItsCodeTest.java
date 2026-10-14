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

import com.swarmcoder.knowledge.ReachableCode;
import com.swarmcoder.verify.BrowserOnlyCode;
import com.swarmcoder.verify.BuildLayout;
import com.swarmcoder.verify.GeneratedContract;
import com.swarmcoder.verify.VerifySpec;
import com.swarmcoder.verify.VerifySpecLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A project whose contract SwarmCoder generated gets the browser block its journeys need from
 * its code: the entry point from the object graph, the classpath from its build, the port in
 * ways that need no knowledge of the application (DEVELOPER_CORRECTIONS section 108).
 *
 * <p>An invented three-module Maven project: a shared module, a client module, and a server
 * module that depends on both and copies its dependencies when it is packaged. No model is
 * called, nothing is built or started, and nothing outside this test's {@code @TempDir} is
 * written.
 */
class AGeneratedContractStartsTheApplicationFromItsCodeTest {

    private static final String SERVER = "server/src/main/java/com/app/server/";

    @TempDir
    Path tree;

    @BeforeEach
    void aThreeModuleProjectWithAGeneratedContract() throws Exception {
        write("pom.xml", """
            <project><artifactId>app</artifactId><packaging>pom</packaging>
              <modules><module>shared</module><module>client</module><module>server</module></modules>
            </project>""");
        write("shared/pom.xml", "<project><artifactId>app-shared</artifactId></project>");
        write("client/pom.xml", """
            <project><artifactId>app-client</artifactId><dependencies>
              <dependency><artifactId>app-shared</artifactId></dependency>
            </dependencies></project>""");
        write("server/pom.xml", """
            <project><artifactId>app-server</artifactId>
              <dependencies>
                <dependency><artifactId>app-shared</artifactId></dependency>
                <dependency><artifactId>app-client</artifactId></dependency>
              </dependencies>
              <build><plugins><plugin>
                <artifactId>maven-dependency-plugin</artifactId>
                <executions><execution>
                  <phase>package</phase>
                  <goals><goal>copy-dependencies</goal></goals>
                  <configuration>
                    <outputDirectory>${project.build.directory}/libs</outputDirectory>
                  </configuration>
                </execution></executions>
              </plugin></plugins></build>
            </project>""");
        write("shared/src/main/java/com/app/shared/Item.java", """
            package com.app.shared;
            public record Item(String name) { }
            """);
        write(SERVER + "Launcher.java", """
            package com.app.server;
            public class Launcher {
                public static void main(String[] args) {
                    System.out.println(new com.app.shared.Item("a"));
                }
            }
            """);
        GeneratedContract.provide(tree, List.of());
        assertThat(contract().browser()).isNull();
        assertThat(GeneratedContract.wantsABrowserBlock(tree)).isTrue();
    }

    @Test
    void oneMainClassIsStartedFromItsModuleWithItsCopiedDependenciesAndToldItsPort() {
        ContractFromTheStart.Served served = serve();

        assertThat(served.question()).isNull();
        assertThat(served.written()).isTrue();
        VerifySpec.BrowserSpec browser = contract().browser();
        assertThat(browser).isNotNull();
        assertThat(browser.serve())
            .startsWith("mvn -o -q -B -Dmaven.test.skip=true -pl server -am package")
            .contains("cd server && ")
            .contains("-cp \"target/classes:target/libs/*\" com.app.server.Launcher")
            .contains("export PORT={PORT}")
            .contains("-Dport={PORT}")
            .doesNotContain("build-classpath");
        assertThat(browser.readyProbe()).isEqualTo("http://127.0.0.1:{PORT}/");
        assertThat(browser.findPort()).isTrue();
        assertThat(browser.readyTimeoutSeconds()).isEqualTo(300);
        assertThat(served.said()).contains("com.app.server.Launcher", "server/target/libs");
    }

    @Test
    void ofTwoMainClassesTheOneInTheModuleThatDependsOnTheMostOthersStartsTheApplication()
            throws Exception {
        write("shared/src/main/java/com/app/shared/Export.java", """
            package com.app.shared;
            public class Export {
                public static void main(String[] args) { }
            }
            """);

        ContractFromTheStart.Served served = serve();

        assertThat(served.question()).isNull();
        assertThat(contract().browser().serve()).contains("com.app.server.Launcher")
            .doesNotContain("Export");
    }

    @Test
    void twoMainClassesNothingDecidesBetweenStopWithAQuestionNamingBoth() throws Exception {
        write(SERVER + "Admin.java", """
            package com.app.server;
            public class Admin {
                public static void main(String[] args) { }
            }
            """);

        ContractFromTheStart.Served served = serve();

        assertThat(served.written()).isFalse();
        assertThat(served.question())
            .contains("com.app.server.Launcher", "com.app.server.Admin")
            .contains(GeneratedContract.ENTRY_POINT_KEY + ": <one of them>");
        assertThat(contract().browser()).as("nothing guessed, nothing written").isNull();

        // The answer the question asks for settles it.
        Path file = tree.resolve(VerifySpecLoader.SPEC_PATH);
        Files.writeString(file, Files.readString(file) + "entryPoint: com.app.server.Admin\n");
        ContractFromTheStart.Served answered = serve();
        assertThat(answered.question()).isNull();
        assertThat(contract().browser().serve()).contains("com.app.server.Admin")
            .doesNotContain("Launcher");
    }

    @Test
    void ofTwoMainClassesInOneModuleTheOneABuildFileNamesStartsTheApplication()
            throws Exception {
        write(SERVER + "Admin.java", """
            package com.app.server;
            public class Admin {
                public static void main(String[] args) { }
            }
            """);
        Path pom = tree.resolve("server/pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("<build>",
            "<properties><start>com.app.server.Admin</start></properties><build>"));

        ContractFromTheStart.Served served = serve();

        assertThat(served.question()).isNull();
        assertThat(contract().browser().serve()).contains("com.app.server.Admin");
    }

    @Test
    void aContractAPersonWroteKeepsItsBrowserBlock() throws Exception {
        Path file = tree.resolve(VerifySpecLoader.SPEC_PATH);
        String handWritten = """
            toolchain: maven
            compile:
              - "mvn -o -q -B test-compile"
            browser:
              serve: "cd server && java -cp target/classes com.app.server.Launcher"
              readyProbe: "http://127.0.0.1:{PORT}/"
              port: 8080
              checks: []
            """;
        Files.writeString(file, handWritten);

        ContractFromTheStart.Served served = serve();

        assertThat(served.written()).isFalse();
        assertThat(served.question()).isNull();
        assertThat(Files.readString(file)).isEqualTo(handWritten);
        assertThat(GeneratedContract.addBrowser(tree, null)).isFalse();
    }

    private ContractFromTheStart.Served serve() {
        ReachableCode.Graph graph = ReachableCode.of(tree);
        return ContractFromTheStart.browserFromTheCode(tree, graph,
            BuildLayout.read(tree, "maven"), BrowserOnlyCode.Survey.NONE);
    }

    private VerifySpec contract() {
        return VerifySpecLoader.load(tree).orElseThrow();
    }

    private void write(String path, String text) throws Exception {
        Path file = tree.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
