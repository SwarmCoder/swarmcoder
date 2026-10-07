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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEVELOPER_CORRECTIONS section 59: run 86's test author read poms and beans.xml whole 13 times.
 * build_of now answers plugin configuration and test-scope dependencies; resources_of answers a
 * module's resource files.
 */
class ABuildFileAndAModulesResourcesAreAnsweredWithoutReadingThemTest {

    @TempDir
    Path world;

    private TreeQueries tree;

    @BeforeEach
    void aModuleWithAConfiguredPluginAndResources() throws Exception {
        Path app = world.resolve("app");
        write(app.resolve("pom.xml"), """
            <project><groupId>com.shop</groupId><artifactId>shop</artifactId><version>1</version>
              <dependencyManagement><dependencies>
                <dependency><groupId>org.junit</groupId><artifactId>junit</artifactId>
                  <version>5.10</version><scope>test</scope></dependency>
              </dependencies></dependencyManagement>
            </project>
            """);
        write(app.resolve("shop-server/pom.xml"), """
            <project>
              <parent><groupId>com.shop</groupId><artifactId>shop</artifactId><version>1</version></parent>
              <groupId>com.shop</groupId><artifactId>shop-server</artifactId><version>1</version>
              <properties><weld.version>5.1</weld.version></properties>
              <dependencies>
                <dependency><groupId>org.junit</groupId><artifactId>junit</artifactId></dependency>
                <dependency><groupId>org.jboss.weld</groupId><artifactId>weld-se</artifactId>
                  <version>${weld.version}</version><scope>test</scope></dependency>
                <dependency><groupId>org.example</groupId><artifactId>runtime-lib</artifactId>
                  <version>2</version></dependency>
              </dependencies>
              <build><plugins><plugin>
                <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
                <configuration><argLine>-Xmx1g</argLine><systemPropertyVariables>
                  <mode>test</mode></systemPropertyVariables></configuration>
                <executions><execution><id>it</id><phase>verify</phase>
                  <goals><goal>test</goal></goals></execution></executions>
              </plugin></plugins></build>
            </project>
            """);
        write(app.resolve("shop-server/src/test/resources/META-INF/beans.xml"),
            "<beans bean-discovery-mode=\"all\"><alternatives/></beans>\n");
        write(app.resolve("shop-server/src/main/resources/app.properties"),
            "# settings\nserver.port=8080\n" + "filler.key=" + "v".repeat(7000) + "\nlast.key=1\n");
        write(app.resolve("shop-server/src/main/resources/META-INF/persistence.xml"),
            "<persistence>" + "<persistence-unit name=\"main\"><class>a.B</class></persistence-unit>"
                + "<persistence-unit name=\"audit\">" + "<!-- -->".repeat(900)
                + "<class>a.C</class></persistence-unit></persistence>");
        KnowledgeCurator curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", app, "local")), null, world.resolve("cache"));
        tree = new TreeQueries(curator);
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void aModulesPluginConfigurationAndExecutionsAreAnswered() {
        assertThat(tree.buildOf("shop-server"))
            .contains("plugin org.apache.maven.plugins:maven-surefire-plugin:3.2.5:")
            .contains("configuration: argLine=-Xmx1g, systemPropertyVariables.mode=test")
            .contains("execution it phase=verify goals=[test]");
    }

    @Test
    void theTestScopeDependenciesAreListedWithTheirResolvedVersionsAndScopes() {
        assertThat(tree.buildOf("shop-server"))
            .contains("test-scope dependencies (2): org.junit:junit:5.10; "
                + "org.jboss.weld:weld-se:5.1")
            .doesNotContain("runtime-lib:2; ");
    }

    @Test
    void aModulesResourceFilesAreListedWithTheirSizes() {
        assertThat(tree.resourcesOf("shop-server"))
            .contains("META-INF/beans.xml", "META-INF/persistence.xml", "app.properties")
            .contains("KB)");
    }

    @Test
    void aSmallResourceIsAnsweredWhole() {
        assertThat(tree.resourcesOf("shop-server/src/test/resources/META-INF/beans.xml"))
            .contains("bean-discovery-mode=\"all\"");
    }

    @Test
    void aLargeResourceIsAnsweredByItsOutlineAndOnePartOnRequest() {
        String outline = tree.resourcesOf("shop-server/src/main/resources/app.properties");
        assertThat(outline).contains("outline").contains("server.port", "filler.key", "last.key")
            .doesNotContain("vvvvvvvvvv");

        assertThat(tree.resourcesOf("shop-server/src/main/resources/app.properties#last.key"))
            .contains("last.key=1");

        String xml = tree.resourcesOf("shop-server/src/main/resources/META-INF/persistence.xml");
        assertThat(xml).contains("persistence-unit name=\"audit\"");
        assertThat(tree.resourcesOf(
            "shop-server/src/main/resources/META-INF/persistence.xml#main"))
            .contains("a.B").doesNotContain("a.C");
        assertThat(tree.resourcesOf("shop-server/src/main/resources/app.properties#L1-2"))
            .contains("# settings").contains("server.port=8080");
    }

    @Test
    void anUnknownModuleIsSaidSo() {
        assertThat(tree.resourcesOf("nowhere")).contains("No module or resource");
    }
}
