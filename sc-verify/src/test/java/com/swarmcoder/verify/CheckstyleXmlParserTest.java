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

import com.swarmcoder.domain.LintResults;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CheckstyleXmlParserTest {

    private static final String CHECKSTYLE_REPORT = """
        <?xml version="1.0" encoding="UTF-8"?>
        <checkstyle version="10.0">
          <file name="src/main/java/A.java">
            <error line="3" severity="error" message="Missing javadoc" source="x"/>
            <error line="9" severity="warning" message="Line too long" source="y"/>
          </file>
          <file name="src/main/java/B.java">
            <error line="1" severity="error" message="Unused import" source="z"/>
          </file>
        </checkstyle>
        """;

    @Test
    void countsSeveritiesAndCollectsMessages() {
        LintResults results = CheckstyleXmlParser.parse(List.of(CHECKSTYLE_REPORT));

        assertThat(results.errors()).isEqualTo(2);
        assertThat(results.warnings()).isEqualTo(1);
        assertThat(results.messages()).hasSize(3);
        assertThat(results.messages().get(0)).contains("A.java:3").contains("Missing javadoc");
    }

    @Test
    void unparseableReportCountsAsError() {
        LintResults results = CheckstyleXmlParser.parse(List.of("<<< not xml"));

        assertThat(results.errors()).isEqualTo(1);
        assertThat(results.messages().get(0)).contains("lint-report-parse-error");
    }

    private static final String SPOTBUGS_REPORT = """
        <?xml version="1.0" encoding="UTF-8"?>
        <BugCollection version="4.8">
          <BugInstance type="NP_NULL_ON_SOME_PATH" priority="1" category="CORRECTNESS">
            <ShortMessage>Possible null dereference</ShortMessage>
            <LongMessage>Possible null pointer dereference of x in App.foo()</LongMessage>
            <SourceLine classname="com.App" sourcepath="com/App.java" start="42"/>
          </BugInstance>
          <BugInstance type="DLS_DEAD_LOCAL_STORE" priority="2" category="STYLE">
            <LongMessage>Dead store to y in App.bar()</LongMessage>
            <SourceLine classname="com.App" sourcepath="com/App.java" start="7"/>
          </BugInstance>
        </BugCollection>
        """;

    @Test
    void spotbugsNativeXmlIsAutoDetectedAndParsed() {
        LintResults results = CheckstyleXmlParser.parse(List.of(SPOTBUGS_REPORT));

        assertThat(results.errors()).isEqualTo(1);   // priority 1 = high = error
        assertThat(results.warnings()).isEqualTo(1);  // priority 2 = normal = warning
        assertThat(results.messages()).hasSize(2);
        assertThat(results.messages().get(0))
            .contains("com/App.java:42").contains("[high]").contains("null pointer dereference");
    }

    @Test
    void mixesCheckstyleAndSpotbugsReports() {
        LintResults results = CheckstyleXmlParser.parse(List.of(CHECKSTYLE_REPORT, SPOTBUGS_REPORT));

        assertThat(results.errors()).isEqualTo(3);   // 2 checkstyle + 1 spotbugs high
        assertThat(results.warnings()).isEqualTo(2);  // 1 checkstyle + 1 spotbugs normal
    }
}
