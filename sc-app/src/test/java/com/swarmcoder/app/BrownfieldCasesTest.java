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
package com.swarmcoder.app;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The case file says what it claims to say — checked without a network, a clone or a build.
 *
 * <p>Everything here is a property the design states about the five jsoup cases and that a later
 * wave silently depends on. A case file that drifts from these is the kind of fault that surfaces
 * three waves later as an oracle nobody can explain, so it is worth a second of build time now.
 */
class BrownfieldCasesTest {

    @Test
    void theShippedCaseFileParsesAndCarriesTheFiveJsoupCases() throws Exception {
        BrownfieldCases.File file = BrownfieldCases.file();

        assertThat(file.target()).isEqualTo("jsoup");
        assertThat(file.cloneUrl()).isEqualTo("https://github.com/jhy/jsoup");
        assertThat(file.cases()).hasSize(5);
        assertThat(file.cases()).extracting(BrownfieldCases.Case::issue)
            .containsExactlyInAnyOrder(2197, 2187, 2266, 2476, 2105);
    }

    /**
     * The narrowed regression gate has to say, in the file, why it is narrowed.
     *
     * <p>jsoup's {@code org.jsoup.integration} tests start a local Jetty and an HTTP proxy and are
     * red on these historical trees before anything is changed. Excluding them is right, and an
     * exclusion whose reason is not written down is how a gate quietly stops gating.
     */
    @Test
    void theNarrowedRegressionGateSaysWhyItIsNarrowed() throws Exception {
        BrownfieldCases.ExistingCorrection correction = BrownfieldCases.file().existingCorrection();

        assertThat(correction).isNotNull();
        assertThat(correction.commands()).hasSize(1);
        assertThat(correction.commands().get(0))
            .as("surefire selects and excludes by PATH pattern; a dotted package name silently "
                + "matches nothing, which would leave the integration tests in the gate")
            .contains("!org/jsoup/integration/**")
            .startsWith("mvn ");
        assertThat(correction.reason())
            .as("the reason is rendered into the committed contract's own comments, so it has to "
                + "carry the measurement rather than an assertion")
            .contains("ProxyTest")
            .contains("2187")
            .hasSizeGreaterThan(300);
    }

    @Test
    void everyCaseCarriesTheIssueTextItselfAndNotAPointerToIt() throws Exception {
        for (BrownfieldCases.Case one : BrownfieldCases.all()) {
            assertThat(one.issueText())
                .as(one.name() + " must carry the issue body verbatim: the harness has to run "
                    + "offline, and a case must not change because somebody edited a GitHub issue")
                .isNotBlank()
                .hasSizeGreaterThan(100);
            assertThat(one.title()).as(one.name() + " needs a title").isNotBlank();
            assertThat(one.why()).as(one.name() + " must say why it qualifies").isNotBlank();
        }
    }

    @Test
    void everyCaseNamesAFixAndTheParentTheSwarmIsGivenInstead() throws Exception {
        for (BrownfieldCases.Case one : BrownfieldCases.all()) {
            assertThat(one.fixCommit())
                .as(one.name() + " needs the fix commit as a full SHA")
                .matches("[0-9a-f]{40}");
            assertThat(one.parentCommit())
                .as(one.name() + " needs the fix's parent as a full SHA — that is the tree the "
                    + "swarm is cut at, and an abbreviation would be ambiguous in six months")
                .matches("[0-9a-f]{40}");
            assertThat(one.parentCommit())
                .as(one.name() + "'s parent must not be the fix itself")
                .isNotEqualTo(one.fixCommit());
        }
    }

    @Test
    void everyCaseNamesTheMaintainersTestAsTheOracleAndTheSourceFilesTheyTouched()
            throws Exception {
        for (BrownfieldCases.Case one : BrownfieldCases.all()) {
            assertThat(one.humanTests())
                .as(one.name() + " has no oracle without at least one of the maintainer's tests")
                .isNotEmpty();
            for (String test : one.humanTests()) {
                assertThat(test)
                    .as(one.name() + "'s oracle must be pkg.Class#method so a build tool can "
                        + "select it")
                    .matches("[A-Za-z0-9_.$]+#[A-Za-z0-9_$]+");
            }
            assertThat(one.humanTestFiles())
                .as(one.name() + " must name the test files the oracle is extracted from")
                .isNotEmpty()
                .allSatisfy(f -> assertThat(f).startsWith("src/test/"));
            assertThat(one.humanSourceFiles())
                .as(one.name() + " must name the source files the human changed, so the report "
                    + "can put them beside the swarm's")
                .isNotEmpty()
                .allSatisfy(f -> assertThat(f).startsWith("src/main/"));
        }
    }

    /**
     * Design §4's selection rule: an issue whose fix introduces a new public method name is only
     * usable when the issue text names that method. Otherwise the maintainer's test calls a name
     * the swarm had no way to choose, and the oracle fails on naming rather than on behaviour.
     *
     * <p>Checked here the only way a file can check it: every case's own {@code why} sentence has
     * to state the finding, so a case added later without thinking about it fails this test.
     */
    @Test
    void everyCaseHasBeenCheckedAgainstTheNewPublicMethodNameRule() throws Exception {
        List<String> silent = new ArrayList<>();
        for (BrownfieldCases.Case one : BrownfieldCases.all()) {
            if (!one.why().toLowerCase().contains("public method")) {
                silent.add(one.name());
            }
        }
        assertThat(silent)
            .as("every case must say in its own words whether its fix introduces a new public "
                + "method name — the oracle is dishonest when it does and the issue text does not "
                + "name it")
            .isEmpty();
    }

    @Test
    void everyCaseIsARunKindTheDeliveryPathAlreadyHas() throws Exception {
        for (BrownfieldCases.Case one : BrownfieldCases.all()) {
            assertThat(one.kind())
                .as(one.name() + " must be a kind RunBrief.forKind already writes a brief for")
                .isIn("BUGFIX", "ENHANCEMENT");
        }
    }

    @Test
    void theCasesCoverMoreThanOneCornerOfTheLibrary() throws Exception {
        List<String> areas = new ArrayList<>();
        for (BrownfieldCases.Case one : BrownfieldCases.all()) {
            for (String file : one.humanSourceFiles()) {
                String area = file.substring(0, file.lastIndexOf('/'));
                if (!areas.contains(area)) {
                    areas.add(area);
                }
            }
        }
        assertThat(areas)
            .as("five cases in one package would measure one corner of the library and read as "
                + "though they measured the product")
            .hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void namingACaseThatIsNotThereSaysSoRatherThanRunningNothing() {
        String previous = System.getProperty(BrownfieldCases.CASE_PROPERTY);
        System.setProperty(BrownfieldCases.CASE_PROPERTY, "9999");
        try {
            assertThat(catchThrowable(BrownfieldCases::selected))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("9999")
                .hasMessageContaining("2187");
        } finally {
            if (previous == null) {
                System.clearProperty(BrownfieldCases.CASE_PROPERTY);
            } else {
                System.setProperty(BrownfieldCases.CASE_PROPERTY, previous);
            }
        }
    }

    @Test
    void selectingOneCaseByIssueNumberReturnsThatCaseAlone() throws Exception {
        String previous = System.getProperty(BrownfieldCases.CASE_PROPERTY);
        System.setProperty(BrownfieldCases.CASE_PROPERTY, "2187");
        try {
            List<BrownfieldCases.Case> selected = BrownfieldCases.selected();
            assertThat(selected).hasSize(1);
            assertThat(selected.get(0).issue()).isEqualTo(2187);
            assertThat(selected.get(0).humanTestClasses())
                .containsExactly("org.jsoup.select.SelectorTest");
        } finally {
            if (previous == null) {
                System.clearProperty(BrownfieldCases.CASE_PROPERTY);
            } else {
                System.setProperty(BrownfieldCases.CASE_PROPERTY, previous);
            }
        }
    }

    private static Throwable catchThrowable(ThrowingCall call) {
        try {
            call.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }
}
