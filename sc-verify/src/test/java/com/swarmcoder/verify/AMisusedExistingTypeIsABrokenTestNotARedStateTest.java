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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.verify.AcceptanceCompileErrors.Kind;
import com.swarmcoder.verify.AcceptanceCompileErrors.PlannedChanges;
import com.swarmcoder.verify.AcceptanceCompileErrors.Reading;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Brownfield harness run 43, 2026-09-26: target jsoup, one task writing {@code Document.java}
 * (and {@code pom.xml}); the acceptance test's line 16 passed a {@code Parser} where jsoup's
 * existing {@code Document} constructor takes a {@code String}. The red check read that compile
 * failure as "references not-yet-implemented symbols — red state confirmed", both workers wrote the
 * right guard, both candidates failed on that line, four repair workers changed nothing, and the
 * task was BLOCKED. {@link AcceptanceCompileErrors} is the reading that tells that apart from the
 * healthy red a test written before its code is.
 */
class AMisusedExistingTypeIsABrokenTestNotARedStateTest {

    private static final String TEST_FILE = "src/test/java/swarm/accept/DocumentTest.java";

    /** Run 43's compile output as Maven prints it on Windows: the error, then the goal summary. */
    private static final String RUN_43_OUTPUT = """
        [INFO] --- compiler:3.13.0:testCompile (default-testCompile) @ jsoup ---
        [ERROR] COMPILATION ERROR :\s
        [ERROR] /C:/Users/dev/.swarmcoder/wt/redcheck-b8074d0f-0/src/test/java/swarm/accept/DocumentTest.java:[16,48] incompatible types: org.jsoup.parser.Parser cannot be converted to java.lang.String
        [INFO] 1 error
        [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:testCompile (default-testCompile) on project jsoup: Compilation failure
        [ERROR] /C:/Users/dev/.swarmcoder/wt/redcheck-b8074d0f-0/src/test/java/swarm/accept/DocumentTest.java:[16,48] incompatible types: org.jsoup.parser.Parser cannot be converted to java.lang.String
        """;

    /** Run 43's plan: one task, the file it fixes plus the pom, and the design's four contracts. */
    private static Task run43Task() {
        Task task = task(Set.of("src/main/java/org/jsoup/nodes/Document.java", "pom.xml"));
        task.setDeliveredContracts(List.of(
            contract("org.jsoup.nodes.Document", "Document charset(Charset charset)", "Charset charset()"),
            contract("org.jsoup.parser.Parser", "String NamespaceXml"),
            contract("org.jsoup.nodes.Document$OutputSettings", "OutputSettings syntax(Syntax syntax)"),
            contract("org.jsoup.nodes.Document$OutputSettings$Syntax", "Syntax xml")));
        return task;
    }

    @Test
    void run43ExactOutputIsABrokenTestNotAHealthyRed() {
        Reading reading = AcceptanceCompileErrors.classify(RUN_43_OUTPUT, List.of(TEST_FILE),
            List.of(run43Task()), PlannedChanges.NONE, e -> "        Document doc = new Document(\"\", Parser.xmlParser());");

        assertThat(reading.inTestFiles()).as("Maven's summary repeat is the same error").hasSize(1);
        assertThat(reading.inTestFiles().get(0).kind()).isEqualTo(Kind.MISUSE);
        assertThat(reading.isBroken())
            .as("the task writes Document.java, but no contract promises to change the constructor")
            .isTrue();
        assertThat(reading.quoted())
            .contains("DocumentTest.java:16: incompatible types: org.jsoup.parser.Parser cannot be "
                + "converted to java.lang.String")
            .contains("no task in this plan promises to change that signature");
        assertThat(reading.headline()).startsWith("DocumentTest.java:16 incompatible types");
    }

    @Test
    void aMisuseStaysHealthyWhenAContractPromisesToChangeThatMember() {
        // The same line, but the plan's contract adds Document(String, Parser) — the pre-change
        // tree lacks it, so a test written against it is a healthy red.
        Map<String, Set<String>> changes = Map.of("org.jsoup.nodes.Document",
            new LinkedHashSet<>(List.of("Document")));

        Reading reading = AcceptanceCompileErrors.classify(RUN_43_OUTPUT, List.of(TEST_FILE),
            List.of(run43Task()), new PlannedChanges(changes),
            e -> "        Document doc = new Document(\"\", Parser.xmlParser());");

        assertThat(reading.isBroken()).isFalse();
    }

    @Test
    void aMisuseWithNoSourceLineAndAnUnrelatedPlannedChangeIsStillBroken() {
        Reading reading = AcceptanceCompileErrors.classify(RUN_43_OUTPUT, List.of(TEST_FILE),
            List.of(run43Task()), new PlannedChanges(Map.of("org.jsoup.nodes.Document",
                Set.of("ensureMetaCharsetElement"))), e -> null);

        assertThat(reading.isBroken()).isTrue();
    }

    @Test
    void aMissingTypeSomeTaskDeliversStaysHealthyRed() {
        String output = """
            src/test/java/swarm/accept/RatingTest.java:5: error: cannot find symbol
              symbol:   class Rating
              location: package com.demo.shared
            1 error
            """;
        Task server = task(Set.of("src/main/java/com/demo/server"));
        server.setDeliveredContracts(List.of(contract("com.demo.shared.Rating")));

        Reading reading = AcceptanceCompileErrors.classify(output,
            List.of("src/test/java/swarm/accept/RatingTest.java"), List.of(server),
            PlannedChanges.NONE, null);

        assertThat(reading.inTestFiles()).hasSize(1);
        assertThat(reading.inTestFiles().get(0).kind()).isEqualTo(Kind.MISSING_TYPE);
        assertThat(reading.inTestFiles().get(0).owner()).isEqualTo("com.demo.shared.Rating");
        assertThat(reading.isBroken()).as("TDD: the code has not been written yet").isFalse();
    }

    @Test
    void aMissingMemberOnATypeTheTaskWritesIsHealthyRed() {
        String output = """
            [ERROR] /w/src/test/java/swarm/accept/DocumentTest.java:[18,12] cannot find symbol
              symbol:   method isEmptyXml()
              location: variable doc of type org.jsoup.nodes.Document
            """;

        Reading reading = AcceptanceCompileErrors.classify(output, List.of(TEST_FILE),
            List.of(task(Set.of("src/main/java/org/jsoup/nodes/Document.java", "pom.xml"))),
            PlannedChanges.NONE, null);

        assertThat(reading.inTestFiles().get(0).kind()).isEqualTo(Kind.MISSING_MEMBER);
        assertThat(reading.inTestFiles().get(0).owner()).isEqualTo("org.jsoup.nodes.Document");
        assertThat(reading.isBroken()).as("the task edits Document.java and may add the method")
            .isFalse();
    }

    @Test
    void aMissingMemberOnAnExistingTypeNobodyWritesOrContractsIsBroken() {
        // The ambiguous case, decided: the member is on an existing type, and no task writes that
        // type's file or names it in a contract. The pom.xml entry does not count — a build file
        // declares no type.
        String output = """
            [ERROR] /w/src/test/java/swarm/accept/DocumentTest.java:[14,30] cannot find symbol
              symbol:   variable NamespaceXmll
              location: class org.jsoup.parser.Parser
            """;

        Reading reading = AcceptanceCompileErrors.classify(output, List.of(TEST_FILE),
            List.of(task(Set.of("src/main/java/org/jsoup/nodes/Document.java", "pom.xml"))),
            PlannedChanges.NONE, null);

        assertThat(reading.isBroken()).isTrue();
        assertThat(reading.quoted()).contains("`org.jsoup.parser.Parser` exists and has no variable "
            + "`NamespaceXmll`");
    }

    @Test
    void aMissingMemberOnAJdkTypeIsAlwaysBroken() {
        String output = """
            src/test/java/swarm/accept/DocumentTest.java:20: error: cannot find symbol
              symbol:   method utf8()
              location: class java.nio.charset.StandardCharsets
            """;
        Task ownsEverything = task(Set.of("src/main/java"));

        Reading reading = AcceptanceCompileErrors.classify(output, List.of(TEST_FILE),
            List.of(ownsEverything), PlannedChanges.NONE, null);

        assertThat(reading.isBroken()).isTrue();
    }

    @Test
    void aNameTheTestNeverImportedIsBrokenUnlessThePlanDeliversIt() {
        // Run 42's temptation: AssertJ's assertThat with no AssertJ on the classpath.
        String output = """
            src/test/java/swarm/accept/DocumentTest.java:22: error: cannot find symbol
              symbol:   method assertThat(java.nio.charset.Charset)
              location: class swarm.accept.DocumentTest
            """;
        Task plain = task(Set.of("src/main/java/org/jsoup/nodes/Document.java"));

        Reading broken = AcceptanceCompileErrors.classify(output, List.of(TEST_FILE),
            List.of(plain), PlannedChanges.NONE, null);
        assertThat(broken.inTestFiles().get(0).kind()).isEqualTo(Kind.UNRESOLVED_NAME);
        assertThat(broken.isBroken()).isTrue();

        Task delivers = task(Set.of("src/main/java/org/jsoup/nodes/Document.java"));
        delivers.setDeliveredContracts(List.of(contract("org.jsoup.helper.Checks",
            "static void assertThat(Charset charset)")));
        Reading healthy = AcceptanceCompileErrors.classify(output, List.of(TEST_FILE),
            List.of(delivers), PlannedChanges.NONE, null);
        assertThat(healthy.isBroken()).as("a static member a contract delivers").isFalse();
    }

    @Test
    void errorsOutsideTheTestFilesAreNotTheTestsAndAnUnreadableAbsenceFailsOpen() {
        String output = """
            src/main/java/org/jsoup/nodes/Element.java:40: error: incompatible types: int cannot be converted to java.lang.String
            src/test/java/swarm/accept/DocumentTest.java:9: error: cannot find symbol
            """;

        Reading reading = AcceptanceCompileErrors.classify(output, List.of(TEST_FILE),
            List.of(task(Set.of("src/main/java/org/jsoup/nodes/Document.java"))),
            PlannedChanges.NONE, null);

        assertThat(reading.inTestFiles()).hasSize(1);
        assertThat(reading.inTestFiles().get(0).kind()).isEqualTo(Kind.UNREADABLE_ABSENCE);
        assertThat(reading.isBroken()).isFalse();
    }

    @Test
    void onlyAMisuseMessageIsAMisuseAtVerification() {
        assertThat(AcceptanceCompileErrors.isMisuseMessage(
            "incompatible types: org.jsoup.parser.Parser cannot be converted to java.lang.String")).isTrue();
        assertThat(AcceptanceCompileErrors.isMisuseMessage(
            "method charset in class org.jsoup.nodes.Document cannot be applied to given types;")).isTrue();
        assertThat(AcceptanceCompileErrors.isMisuseMessage(
            "unreported exception java.io.IOException; must be caught or declared to be thrown")).isTrue();
        assertThat(AcceptanceCompileErrors.isMisuseMessage(
            "refers to com.demo.shared.Rating, which does not exist")).isFalse();
        assertThat(AcceptanceCompileErrors.isMisuseMessage(
            "cannot find symbol: method isEmptyXml in variable doc of type org.jsoup.nodes.Document"))
            .isFalse();
        assertThat(AcceptanceCompileErrors.isMisuseMessage("package com.zeroz4j.ui does not exist")).isFalse();
        assertThat(AcceptanceCompileErrors.isMisuseMessage(" ")).isFalse();
    }

    @Test
    void aBuildFileInAWriteSetDeliversNoType() {
        // Before run 43, pom.xml read as "an unreadable layout, may write anything".
        Task task = task(Set.of("src/main/java/org/jsoup/nodes/Document.java", "pom.xml"));

        assertThat(TypeDeliverability.undeliverable(List.of("com.acme.Nowhere"), List.of(task)))
            .containsExactly("com.acme.Nowhere");
        assertThat(TypeDeliverability.undeliverable(List.of("org.jsoup.nodes.Sibling"), List.of(task)))
            .as("a file entry still covers its own package").isEmpty();
        assertThat(TypeDeliverability.mayWrite(task, "org.jsoup.nodes.Document")).isTrue();
        assertThat(TypeDeliverability.mayWrite(task, "org.jsoup.nodes.Element"))
            .as("adding a member needs the type's own file").isFalse();
        assertThat(TypeDeliverability.mayWrite(task(Set.of("src/main/java/org/jsoup")),
            "org.jsoup.nodes.Element")).isTrue();
    }

    @Test
    void theRedCheckKeepsTheCompilerOutputForTheClassifier() {
        FakeExecTarget target = new FakeExecTarget().scriptOutput("gradlew acceptanceTest", 1,
            RUN_43_OUTPUT);
        VerifySpec spec = new VerifySpec("gradle", null, List.of("gradlew acceptanceTest"), null,
            null, null, null, 60, null);

        RedChecker.RedCheckResult result = new RedChecker().check(target, spec);

        assertThat(result.red()).isTrue();
        assertThat(result.compileFailure()).isTrue();
        assertThat(result.compileOutput()).contains("DocumentTest.java:[16,48] incompatible types");
    }

    // ---------------------------------------------------------------------------------------------

    private static Task task(Set<String> writeSet) {
        return new Task(UUID.randomUUID(), 1, "Guard Document.ensureMetaCharsetElement against empty "
            + "XML documents", "do it", writeSet, Set.of(), List.of(), "src/test/java/swarm", null,
            null, new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
    }

    private static ApiContract contract(String typeName, String... members) {
        return new ApiContract(UUID.randomUUID(), typeName.substring(typeName.lastIndexOf('.') + 1),
            "", "", typeName, List.of(members));
    }
}
