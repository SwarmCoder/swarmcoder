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

/**
 * Harness run 65 (2026-10-02): a task delivered a class with a private constructor and instance
 * methods only, which compiles and which nothing can call. {@link
 * JavaSourceFacts#unusableFromOutside} names exactly that shape and lets everything else through.
 */
class AClassNobodyCanUseIsNamedTest {

    private static String verdict(String source, String type) {
        return JavaSourceFacts.of(source).unusableFromOutside(type);
    }

    @Test
    void aPrivateConstructorWithOnlyInstanceMembersIsUnusable() {
        String verdict = verdict("""
            package com.acme.i18n;
            public final class LogbookTexts {
                private LogbookTexts() { }
                public String saveLabel() { return "Save"; }
                public String fieldLabel(String fieldName) { return fieldName; }
            }
            """, "LogbookTexts");

        assertThat(verdict).contains("class LogbookTexts cannot be used by any other class")
            .contains("saveLabel, fieldLabel");
    }

    @Test
    void everyShapeThatCanBeUsedIsLetThrough() {
        assertThat(verdict("""
            public final class Texts {
                private Texts() { }
                public static String saveLabel() { return "Save"; }
            }
            """, "Texts")).as("static members").isNull();
        assertThat(verdict("""
            public final class Texts {
                public static final Texts INSTANCE = new Texts();
                private Texts() { }
                public String saveLabel() { return "Save"; }
            }
            """, "Texts")).as("a shared instance").isNull();
        assertThat(verdict("""
            public class Texts {
                private Texts() { }
                public static Texts of() { return new Texts(); }
                public String saveLabel() { return "Save"; }
            }
            """, "Texts")).as("a static factory").isNull();
        assertThat(verdict("""
            public class Texts {
                public String saveLabel() { return "Save"; }
            }
            """, "Texts")).as("the default constructor").isNull();
        assertThat(verdict("""
            public class Texts {
                Texts() { }
                public String saveLabel() { return "Save"; }
            }
            """, "Texts")).as("a package-private constructor").isNull();
        assertThat(verdict("""
            public class Texts {
                private Texts() { }
                public String saveLabel() { return "Save"; }
                public static class Builder { public Texts build() { return new Texts(); } }
            }
            """, "Texts")).as("a nested builder").isNull();
        assertThat(verdict("""
            @Component
            public class Texts {
                private Texts() { }
                public String saveLabel() { return "Save"; }
            }
            """, "Texts")).as("an annotated class: a framework may construct it").isNull();
        assertThat(verdict("""
            public abstract class Texts {
                private Texts() { }
                public String saveLabel() { return "Save"; }
            }
            """, "Texts")).as("an abstract class").isNull();
        assertThat(verdict("""
            public enum Texts { SAVE; private Texts() { } public String label() { return "x"; } }
            """, "Texts")).as("an enum").isNull();
        assertThat(verdict("""
            public final class Constants {
                private Constants() { }
            }
            """, "Constants")).as("nothing to reach is not this defect").isNull();
    }
}
