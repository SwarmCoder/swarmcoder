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

import com.swarmcoder.domain.ApiContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The delivery check fails a candidate only on something it has established. Java gives a type
 * members nobody writes down (a default constructor, a record's canonical constructor, an enum's
 * {@code values()}, everything inherited), lets a type be nested, generated or completed at build
 * time, and lets a package be called {@code build}. Each of those was a way for a correct candidate
 * to be reported as "not delivered"; each is a case here.
 *
 * <p>Audit of 2026-10-02 (branch fix/audit-the-gates), after runs 71 and 72 each lost a live run
 * to one such reading.
 */
class ContractDeliveryReadsJavaAsJavaTest {

    @TempDir
    Path tree;

    @Test
    void aPromisedNoArgumentConstructorIsDeliveredByAClassThatDeclaresNoConstructor() throws Exception {
        write("src/main/java/com/acme/Qso.java", """
            package com.acme;
            public class Qso {
                private String call;
                public String getCall() { return call; }
            }
            """);

        assertThat(shortfall("com.acme.Qso", "Qso()", "String getCall()")).isEmpty();
    }

    @Test
    void aPromisedNoArgumentConstructorIsMissingWhenOnlyAnotherConstructorIsDeclared() throws Exception {
        write("src/main/java/com/acme/Qso.java", """
            package com.acme;
            public class Qso {
                public Qso(String call) { }
            }
            """);

        assertThat(shortfall("com.acme.Qso", "Qso()")).contains("without the member Qso()");
    }

    @Test
    void aRecordDeliversItsCanonicalConstructorAndAccessors() throws Exception {
        write("src/main/java/com/acme/Point.java", """
            package com.acme;
            public record Point(int x, java.util.List<String> labels) { }
            """);

        assertThat(shortfall("com.acme.Point", "Point(int x, List<String> labels)", "int x()",
            "List<String> labels()", "boolean equals(Object other)", "String toString()")).isEmpty();
    }

    @Test
    void anEnumDeliversValuesAndValueOf() throws Exception {
        write("src/main/java/com/acme/State.java", """
            package com.acme;
            public enum State { ON, OFF }
            """);

        assertThat(shortfall("com.acme.State", "ON", "static State[] values()",
            "static State valueOf(String name)", "String name()", "int ordinal()")).isEmpty();
    }

    @Test
    void aMemberInheritedFromATypeOfThisProjectIsDelivered() throws Exception {
        write("src/main/java/com/acme/Base.java", """
            package com.acme;
            public abstract class Base implements Named {
                public String id() { return "x"; }
            }
            """);
        write("src/main/java/com/acme/Named.java", """
            package com.acme;
            public interface Named {
                default String name() { return "n"; }
            }
            """);
        write("src/main/java/com/acme/impl/Thing.java", """
            package com.acme.impl;
            import com.acme.Base;
            public class Thing extends Base {
                public int size() { return 1; }
            }
            """);

        assertThat(shortfall("com.acme.impl.Thing", "String id()", "String name()", "int size()"))
            .isEmpty();
        assertThat(shortfall("com.acme.impl.Thing", "String colour()"))
            .contains("without the member String colour()");
    }

    @Test
    void aMemberThatMayComeFromASupertypeOutsideThisProjectIsNotReportedMissing() throws Exception {
        write("src/main/java/com/acme/Screen.java", """
            package com.acme;
            import com.library.ui.Composite;
            public class Screen extends Composite {
                public void open() { }
            }
            """);

        // render() may well be Composite's: nothing here can say it is not
        assertThat(shortfall("com.acme.Screen", "void open()", "Component render()")).isEmpty();
        // ...but to the question "what may a task still have to add?" it counts: the red check
        // must not call a test broken for using a member the plan is about to write
        ApiContract contract = new ApiContract(null, "Screen", "", "", "com.acme.Screen",
            List.of("void open()", "Component render()"));
        assertThat(ContractDelivery.notYetThere(tree, List.of(contract))).singleElement()
            .satisfies(s -> assertThat(s.missingMembers()).containsExactly("Component render()"));
    }

    @Test
    void aMemberOfAJdkSupertypeIsDeliveredAndOneItDoesNotHaveIsMissing() throws Exception {
        write("src/main/java/com/acme/Failure.java", """
            package com.acme;
            public class Failure extends RuntimeException {
                public Failure(String message) { super(message); }
            }
            """);

        assertThat(shortfall("com.acme.Failure", "Failure(String message)", "String getMessage()"))
            .isEmpty();
        assertThat(shortfall("com.acme.Failure", "String code()"))
            .contains("without the member String code()");
    }

    @Test
    void aNestedTypeIsFoundByItsDottedOrItsBinaryName() throws Exception {
        write("src/main/java/com/acme/Logbook.java", """
            package com.acme;
            public class Logbook {
                public static class Entry {
                    public String call() { return ""; }
                }
            }
            """);

        assertThat(shortfall("com.acme.Logbook.Entry", "String call()")).isEmpty();
        assertThat(shortfall("com.acme.Logbook$Entry", "String call()")).isEmpty();
        assertThat(shortfall("com.acme.Logbook.Entry", "String band()"))
            .contains("without the member String band()");
    }

    @Test
    void aGenericMethodIsTheMethodPromised() throws Exception {
        write("src/main/java/com/acme/Finder.java", """
            package com.acme;
            import java.util.List;
            import java.util.Map;
            public class Finder {
                public <T> T find(Class<T> type) { return null; }
                public static <T extends Comparable<T>> List<T> sorted(List<T> in) { return in; }
                public Map<String, String> fieldSource() { return null; }
                public void log(String first, Object... rest) { }
                public void accept(final @Deprecated java.util.function.Consumer<? super String> sink) { }
            }
            """);

        assertThat(shortfall("com.acme.Finder", "<T> T find(Class<T> type)",
            "static <T extends Comparable<T>> List<T> sorted(List<T> in)",
            "java.util.Map<String,String> fieldSource()", "void log(String first, Object... rest)",
            "void accept(Consumer<String> sink)")).isEmpty();
    }

    @Test
    void aTypeInAPackageNamedLikeABuildFolderIsStillRead() throws Exception {
        write("src/main/java/com/acme/build/Plan.java", """
            package com.acme.build;
            public class Plan {
                public int steps() { return 0; }
            }
            """);
        write("src/main/java/com/acme/target/Aim.java", """
            package com.acme.target;
            public interface Aim { }
            """);

        assertThat(shortfall("com.acme.build.Plan", "int steps()")).isEmpty();
        assertThat(shortfall("com.acme.target.Aim")).isEmpty();
    }

    @Test
    void aMemberTheBuildAddedToAHandWrittenClassIsDelivered() throws Exception {
        // what an annotation processor that completes a class (accessors for annotated fields)
        // leaves behind: the source has the field, the compiled class has the method
        write("mod/src/main/java/com/acme/Station.java", """
            package com.acme;
            @Accessors
            public class Station {
                private String call;
            }
            """);
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/acme/Station", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "call", "Ljava/lang/String;", null, null).visitEnd();
        cw.visitMethod(Opcodes.ACC_PUBLIC, "getCall", "()Ljava/lang/String;", null, null).visitEnd();
        cw.visitEnd();
        Path clazz = tree.resolve("mod/target/classes/com/acme/Station.class");
        Files.createDirectories(clazz.getParent());
        Files.write(clazz, cw.toByteArray());

        assertThat(shortfall("com.acme.Station", "String getCall()")).isEmpty();
        assertThat(shortfall("com.acme.Station", "String getBand()"))
            .contains("without the member String getBand()");
    }

    @Test
    void anAnnotationTypesElementsAreItsMembers() throws Exception {
        write("src/main/java/com/acme/Limit.java", """
            package com.acme;
            public @interface Limit {
                int max() default 10;
                String message();
            }
            """);

        assertThat(shortfall("com.acme.Limit", "int max()", "String message()",
            "int max() default 10")).isEmpty();
    }

    private String shortfall(String type, String... members) {
        ApiContract contract = new ApiContract(null, type, "", "", type, List.of(members));
        return ContractDelivery.describe(ContractDelivery.shortfalls(tree, List.of(contract)));
    }

    private void write(String relative, String source) throws Exception {
        Path file = tree.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }
}
