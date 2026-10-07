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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker is shown the acceptance test methods its task claims and the helpers they use, not
 * the whole file (owner's decision 2026-10-05, DEVELOPER_CORRECTIONS section 61).
 */
class AcceptanceTestReadTest {

    private static final String SOURCE = """
        package swarm.accept;

        import org.junit.jupiter.api.BeforeEach;
        import org.junit.jupiter.api.Test;

        class UsabilityTest {
            private static final String SHORTCUT_RULE = "every field carries a keyboard shortcut";
            private Object screen;

            @BeforeEach
            void build() {
                screen = new Object();
            }

            @Test
            void usableFromKeyboard() {
                assertThat(shortcutOf(screen)).as(SHORTCUT_RULE).isNotNull();
            }

            @Test
            void someOtherThing() {
                assertThat(unrelatedHelper()).isTrue();
            }

            private String shortcutOf(Object screen) {
                return lookupKey(screen);
            }

            private String lookupKey(Object screen) {
                return "k";
            }

            private boolean unrelatedHelper() {
                return true;
            }
        }
        """;

    @Test
    void theClaimedMethodAndTheHelpersItUsesAreShown() {
        String shown = AcceptanceTestRead.of("app/src/test/java/swarm/accept/UsabilityTest.java",
            SOURCE, Set.of("usableFromKeyboard"));

        assertThat(shown).contains("void usableFromKeyboard()")
            .contains("SHORTCUT_RULE = \"every field carries a keyboard shortcut\"")
            .as("a helper the claimed method calls").contains("String shortcutOf(Object screen)")
            .as("a helper of a helper").contains("String lookupKey(Object screen)")
            .as("what runs before every test").contains("void build()")
            .contains("import org.junit.jupiter.api.Test;")
            .as("the field build() and the claimed method use").contains("private Object screen");
    }

    @Test
    void aMethodNobodyClaimedAndItsPrivateHelperAreLeftOut() {
        String shown = AcceptanceTestRead.of("UsabilityTest.java", SOURCE,
            Set.of("usableFromKeyboard"));

        assertThat(shown).doesNotContain("someOtherThing").doesNotContain("unrelatedHelper");
    }

    @Test
    void noClaimedMethodsMeansEveryTestMethod() {
        String shown = AcceptanceTestRead.of("UsabilityTest.java", SOURCE, Set.of());

        assertThat(shown).contains("usableFromKeyboard").contains("someOtherThing")
            .contains("unrelatedHelper");
    }

    @Test
    void aFileThatDeclaresNoneOfThemSaysSo() {
        assertThat(AcceptanceTestRead.of("UsabilityTest.java", SOURCE, Set.of("gone")))
            .contains(AcceptanceTestRead.NO_SUCH_METHOD);
    }
}
