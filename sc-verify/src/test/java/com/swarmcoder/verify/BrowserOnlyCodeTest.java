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

import com.swarmcoder.domain.TestFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness run 37, 2026-09-25: an acceptance test in {@code bookshelf-demo-server} called
 * {@code BookStore} in {@code bookshelf-demo-client}, a TeaVM module, and every candidate died with
 * {@code UnsatisfiedLinkError: org.teavm.jso.browser.Window.current()} (Native Method). These pin
 * the two things {@link BrowserOnlyCode} has to know to stop that: which modules of a build run
 * only in a browser (read from each module's OWN build file — the server that packages the client
 * bundle is not one), and what a failure looks like when a JUnit test reached such code.
 *
 * <p>The build here is the Bookshelf fixture's shape exactly: shared, client (declares
 * {@code org.teavm:teavm-classlib} at provided scope and the {@code teavm-maven-plugin}), and a
 * server that depends on the client at provided scope.
 */
class BrowserOnlyCodeTest {

    @TempDir
    Path repo;

    /** Run 37's failure, as the verdict reading recorded it. */
    static final String RUN_37_TRACE = """
        java.lang.UnsatisfiedLinkError: 'org.teavm.jso.browser.Window org.teavm.jso.browser.Window.current()'
        \tat org.teavm.jso.browser.Window.current(Native Method)
        \tat com.swarmcoder.demo.bookshelf.client.BookStore.<init>(BookStore.java:31)
        \tat com.swarmcoder.demo.bookshelf.client.BookStore.getInstance(BookStore.java:22)
        \tat swarm.accept.BookPersistenceTest.dataSurvivesBrowserRestart(BookPersistenceTest.java:26)""";

    @Test
    void theTeaVmClientIsBrowserOnlyAndTheServerThatPackagesItIsNot() throws Exception {
        bookshelf();
        BrowserOnlyCode.Survey survey = BrowserOnlyCode.survey(repo, BuildLayout.read(repo, "maven"));

        assertThat(survey.any()).isTrue();
        assertThat(survey.browserOnlyDirs()).containsExactly("bookshelf-demo-client");
        assertThat(survey.jvmModules()).containsExactly("bookshelf-demo-shared", "bookshelf-demo-server");
        BrowserOnlyCode.Module client = survey.browserOnly().get(0);
        assertThat(client.evidence()).isEqualTo("declares org.teavm:teavm-classlib");
        assertThat(client.runtime()).contains("TeaVM");
        assertThat(client.packages()).containsExactly("com.swarmcoder.demo.bookshelf.client");
    }

    @Test
    void aNameIsTracedToTheBrowserOnlyModuleEvenBeforeTheTypeExists() throws Exception {
        bookshelf();
        BrowserOnlyCode.Survey survey = BrowserOnlyCode.survey(repo, BuildLayout.read(repo, "maven"));

        // Run 37's BookStore did not exist when the test was written; its package did.
        assertThat(survey.moduleOwning("com.swarmcoder.demo.bookshelf.client.BookStore"))
            .isNotNull()
            .extracting(BrowserOnlyCode.Module::dir).isEqualTo("bookshelf-demo-client");
        // A new sub-package of the client is still the client.
        assertThat(survey.moduleOwning("com.swarmcoder.demo.bookshelf.client.store.Cache")).isNotNull();
        // The server, the shared model, a brand-new package nobody owns, and a name that merely
        // starts with the same letters, are not.
        assertThat(survey.moduleOwning("com.swarmcoder.demo.bookshelf.server.BookServiceImpl")).isNull();
        assertThat(survey.moduleOwning("com.swarmcoder.demo.bookshelf.model.Message")).isNull();
        assertThat(survey.moduleOwning("com.swarmcoder.demo.bookshelf.shared.Book")).isNull();
        assertThat(survey.moduleOwning("com.swarmcoder.demo.bookshelf.clientele.X")).isNull();
    }

    @Test
    void aSplitPackageIsNeverCalledBrowserOnly() throws Exception {
        bookshelf();
        write("bookshelf-demo-server/src/main/java/com/swarmcoder/demo/bookshelf/client/Bridge.java",
            "package com.swarmcoder.demo.bookshelf.client; class Bridge {}");
        BrowserOnlyCode.Survey survey = BrowserOnlyCode.survey(repo, BuildLayout.read(repo, "maven"));

        assertThat(survey.moduleOwning("com.swarmcoder.demo.bookshelf.client.BookStore")).isNull();
    }

    @Test
    void aBrowserRuntimeOnlyInTestScopeDoesNotMakeAModuleBrowserOnly() throws Exception {
        write("pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>a</artifactId>
              <dependencies>
                <dependency><groupId>org.teavm</groupId><artifactId>teavm-junit</artifactId>
                  <scope>test</scope></dependency>
              </dependencies>
            </project>
            """);
        write("src/main/java/com/acme/App.java", "package com.acme; class App {}");

        assertThat(BrowserOnlyCode.survey(repo, BuildLayout.read(repo, "maven")).any()).isFalse();
    }

    @Test
    void aGradleModuleNamingTeaVmIsBrowserOnly() throws Exception {
        write("settings.gradle", "include 'web', 'api'\n");
        write("web/build.gradle", "plugins { id 'org.teavm' version '0.10.2' }\n");
        write("api/build.gradle", "// org.teavm is not used here\ndependencies { }\n");
        write("web/src/main/java/com/acme/web/Page.java", "package com.acme.web; class Page {}");
        write("api/src/main/java/com/acme/api/Api.java", "package com.acme.api; class Api {}");

        BrowserOnlyCode.Survey survey = BrowserOnlyCode.survey(repo, BuildLayout.read(repo, "gradle"));

        assertThat(survey.browserOnlyDirs()).containsExactly("web");
        assertThat(survey.moduleOwning("com.acme.web.Page")).isNotNull();
        assertThat(survey.moduleOwning("com.acme.api.Api")).isNull();
    }

    @Test
    void anUndeterminedLayoutKnowsNothingAndSaysSo() {
        assertThat(BrowserOnlyCode.survey(repo, BuildLayout.read(repo, "maven")))
            .isSameAs(BrowserOnlyCode.Survey.NONE);
        assertThat(BrowserOnlyCode.Survey.NONE.moduleOwning("com.x.client.A")).isNull();
    }

    // ------------------------------------------------------------------ failure reading

    @Test
    void run37sFailureIsReadAsReachingBrowserOnlyCode() {
        String reached = BrowserOnlyCode.reachedIn(new TestFailure(
            "swarm.accept.BookPersistenceTest#dataSurvivesBrowserRestart",
            "'org.teavm.jso.browser.Window org.teavm.jso.browser.Window.current()'", RUN_37_TRACE));

        assertThat(reached)
            .contains("can only run in a browser")
            .contains("UnsatisfiedLinkError")
            .contains("org.teavm.jso.browser.Window belongs to a browser runtime");
    }

    @Test
    void aBrowserRuntimeClassThatCannotBeLoadedIsTheSameFinding() {
        String trace = """
            java.lang.NoClassDefFoundError: org/teavm/jso/JSObject
            \tat com.acme.client.Store.<init>(Store.java:9)
            Caused by: java.lang.ClassNotFoundException: org.teavm.jso.JSObject""";

        assertThat(BrowserOnlyCode.reachedIn(new TestFailure("swarm.accept.T#t",
            "org/teavm/jso/JSObject", trace))).contains("cannot load");
    }

    @Test
    void anUnsatisfiedLinkErrorDeepInACauseChainStillCounts() {
        String trace = """
            java.lang.ExceptionInInitializerError
            \tat com.acme.client.Store.<clinit>(Store.java:5)
            Caused by: java.lang.UnsatisfiedLinkError: 'void com.acme.native.Lib.init()'""";

        assertThat(BrowserOnlyCode.reachedIn(new TestFailure("swarm.accept.T#t", "", trace)))
            .isNotNull();
    }

    @Test
    void anAssertionOrAnOrdinaryMissingClassIsNot() {
        assertThat(BrowserOnlyCode.reachedIn(new TestFailure("swarm.accept.T#t",
            "expected: <1> but was: <0>", """
            org.opentest4j.AssertionFailedError: expected: <1> but was: <0>
            \tat swarm.accept.T.t(T.java:10)"""))).isNull();
        // A candidate CAN fix this one — it is a dependency it forgot to declare.
        assertThat(BrowserOnlyCode.reachedIn(new TestFailure("swarm.accept.T#t",
            "com/google/gson/Gson", """
            java.lang.NoClassDefFoundError: com/google/gson/Gson
            \tat com.acme.server.Json.<init>(Json.java:4)"""))).isNull();
        assertThat(BrowserOnlyCode.reachedIn(null)).isNull();
    }

    @Test
    void theBrowserRuntimesAreRecognisedByPackage() {
        assertThat(BrowserOnlyCode.isBrowserRuntime("org.teavm.jso.browser.Window")).isTrue();
        assertThat(BrowserOnlyCode.isBrowserRuntime("com.zeroz4j.client.Zeroz4jClient")).isTrue();
        assertThat(BrowserOnlyCode.isBrowserRuntime("com.zeroz4j.ui.Button")).isTrue();
        assertThat(BrowserOnlyCode.isBrowserRuntime("elemental2.dom.DomGlobal")).isTrue();
        assertThat(BrowserOnlyCode.isBrowserRuntime("com.zeroz4j.api.RmiService")).isFalse();
        assertThat(BrowserOnlyCode.isBrowserRuntime("com.zeroz4j.server.test.TestServer")).isFalse();
    }

    // ------------------------------------------------------------------ the fixture

    /** The Bookshelf demo's three poms, trimmed to what decides the answer. */
    private void bookshelf() throws Exception {
        write("pom.xml", """
            <project><modelVersion>4.0.0</modelVersion>
              <groupId>com.swarmcoder.demo</groupId><artifactId>bookshelf-demo</artifactId>
              <version>1</version><packaging>pom</packaging>
              <modules>
                <module>bookshelf-demo-shared</module>
                <module>bookshelf-demo-client</module>
                <module>bookshelf-demo-server</module>
              </modules>
            </project>
            """);
        write("bookshelf-demo-shared/pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><artifactId>bookshelf-demo-shared</artifactId>
              <dependencies>
                <dependency><groupId>com.zeroz4j</groupId><artifactId>zerozstack-shared-api</artifactId></dependency>
              </dependencies>
            </project>
            """);
        write("bookshelf-demo-client/pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><artifactId>bookshelf-demo-client</artifactId>
              <dependencies>
                <!-- org.teavm in a comment decides nothing -->
                <dependency><groupId>org.teavm</groupId><artifactId>teavm-classlib</artifactId>
                  <scope>provided</scope></dependency>
                <dependency><groupId>com.swarmcoder.demo</groupId><artifactId>bookshelf-demo-shared</artifactId></dependency>
                <dependency><groupId>com.zeroz4j</groupId><artifactId>zerozstack-client</artifactId></dependency>
              </dependencies>
              <build><plugins>
                <plugin><groupId>org.teavm</groupId><artifactId>teavm-maven-plugin</artifactId></plugin>
              </plugins></build>
            </project>
            """);
        write("bookshelf-demo-server/pom.xml", """
            <project><modelVersion>4.0.0</modelVersion><artifactId>bookshelf-demo-server</artifactId>
              <dependencies>
                <dependency><groupId>com.swarmcoder.demo</groupId><artifactId>bookshelf-demo-shared</artifactId></dependency>
                <dependency><groupId>com.swarmcoder.demo</groupId><artifactId>bookshelf-demo-client</artifactId>
                  <scope>provided</scope></dependency>
                <dependency><groupId>com.zeroz4j</groupId><artifactId>zerozstack-server-helidon</artifactId></dependency>
              </dependencies>
            </project>
            """);
        write("bookshelf-demo-shared/src/main/java/com/swarmcoder/demo/bookshelf/model/Message.java",
            "package com.swarmcoder.demo.bookshelf.model; public class Message {}");
        write("bookshelf-demo-client/src/main/java/com/swarmcoder/demo/bookshelf/client/ClientApp.java",
            "package com.swarmcoder.demo.bookshelf.client; public class ClientApp {}");
        write("bookshelf-demo-server/src/main/java/com/swarmcoder/demo/bookshelf/server/ServerApp.java",
            "package com.swarmcoder.demo.bookshelf.server; public class ServerApp {}");
    }

    private void write(String path, String content) throws Exception {
        Path target = repo.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
