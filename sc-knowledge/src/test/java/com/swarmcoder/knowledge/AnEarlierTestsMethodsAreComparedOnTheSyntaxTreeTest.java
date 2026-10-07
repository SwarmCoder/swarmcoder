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

import static org.assertj.core.api.Assertions.assertThat;

/** DEVELOPER_CORRECTIONS section 59: what a revised test source takes from an earlier one. */
class AnEarlierTestsMethodsAreComparedOnTheSyntaxTreeTest {

    private static final String EARLIER = """
        package swarm.accept;

        import org.junit.jupiter.api.Test;

        class LogbookTest {
            // the first story
            @Test
            void recordsAnEntry() {
                assertEquals(1, 1);
            }

            @Test
            void listsEntries() {
                assertEquals("a", "a");
            }

            private int helper() { return 1; }
        }
        """;

    @Test
    void addingATestMethodAndTouchingCommentsAndWhitespaceTakesNothing() {
        String revised = EARLIER
            .replace("// the first story", "/* same test, reformatted */")
            .replace("assertEquals(1, 1);", "assertEquals(1,\n                1);")
            .replace("private int helper", "@Test\n    void addsMore() {}\n\n    private int helper");

        assertThat(TestMethods.lostBy(EARLIER, revised)).isEmpty();
    }

    @Test
    void aRemovedTestMethodAndAChangedOneAreEachNamed() {
        String revised = EARLIER
            .replace("assertEquals(\"a\", \"a\");", "assertEquals(\"a\", \"b\");")
            .replace("@Test\n    void recordsAnEntry() {\n        assertEquals(1, 1);\n    }\n", "");

        assertThat(TestMethods.lostBy(EARLIER, revised))
            .containsExactly("removed: LogbookTest#recordsAnEntry()",
                "changed: LogbookTest#listsEntries()");
    }

    @Test
    void aTestMethodInANestedClassIsAddressedThroughItsType() {
        String nested = "package p;\nclass Outer {\n  @org.junit.jupiter.api.Nested class Inner {\n"
            + "    @Test void works() {}\n  }\n}\n";

        assertThat(TestMethods.of(nested).keySet()).containsExactly("Outer.Inner#works()");
        assertThat(TestMethods.ids(nested)).containsExactly("p.Outer$Inner#works");
    }

    @Test
    void idsAreTheFormTheTestRunnerReports() {
        assertThat(TestMethods.ids(EARLIER))
            .containsExactly("swarm.accept.LogbookTest#recordsAnEntry",
                "swarm.accept.LogbookTest#listsEntries");
    }
}
