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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 82, 2026-10-04: workers read 36 files whole. A worker changed code with a unified
 * diff (five were rejected) or by writing the whole file back, and both need the file read
 * first. One member is now read, replaced or added by its name in the checkout as it is on disk.
 */
class CheckoutCodeTest {

    @TempDir
    Path checkout;

    private Path file;

    private static final String REL = "shop-server/src/main/java/com/shop/server/Ledger.java";

    @BeforeEach
    void aCheckout() throws Exception {
        file = checkout.resolve(REL);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            package com.shop.server;

            import java.util.ArrayList;
            import java.util.List;

            public class Ledger {

                private final List<String> entries = new ArrayList<>();

                /** Appends one entry. */
                public void append(String entry) {
                    entries.add(entry);
                }

                public void append(String entry, int times) {
                    for (int i = 0; i < times; i++) {
                        append(entry);
                    }
                }

                public int size() {
                    return entries.size();
                }
            }
            """);
    }

    @Test
    void oneMemberIsReplacedByNameAndTheRestOfTheFileIsUntouched() throws Exception {
        CheckoutCode.Edit edit = CheckoutCode.replaceMember(checkout, "Ledger#size", """
            import java.util.Objects;

            public int size() {
                return (int) entries.stream().filter(Objects::nonNull).count();
            }
            """);

        assertThat(edit.changed()).isTrue();
        assertThat(edit.file()).isEqualTo(REL);
        assertThat(edit.answer()).startsWith("replaced size in " + REL);
        assertThat(edit.content())
            .contains("    public int size() {\n        return (int) entries.stream()"
                + ".filter(Objects::nonNull).count();\n    }\n}")
            .contains("import java.util.List;\nimport java.util.Objects;\n")
            .contains("/** Appends one entry. */\n    public void append(String entry) {")
            .doesNotContain("return entries.size();");
        assertThat(Files.readString(file)).as("the caller writes, under its own policy")
            .contains("return entries.size();");
    }

    @Test
    void anOverloadIsChosenByTheNewTextsParametersOrByItsLine() {
        CheckoutCode.Edit bySignature = CheckoutCode.replaceMember(checkout,
            "com.shop.server.Ledger#append",
            "public void append(String entry, int times) {\n    entries.add(entry + times);\n}");
        assertThat(bySignature.content()).contains("entries.add(entry + times);")
            .contains("entries.add(entry);").doesNotContain("for (int i = 0");

        CheckoutCode.Edit neither = CheckoutCode.replaceMember(checkout, "Ledger#append",
            "public void append(long entry) {\n}");
        assertThat(neither.changed()).isFalse();
        assertThat(neither.answer()).contains("2 overloads", "// line 11", "// line 15");

        CheckoutCode.Edit byLine = CheckoutCode.replaceMember(checkout, "Ledger#append:11",
            "public void append(long entry) {\n}");
        assertThat(byLine.content()).contains("public void append(long entry) {\n    }")
            .contains("for (int i = 0");
    }

    @Test
    void aMemberIsAddedBeforeTheTypesClosingBraceAtTheTypesOwnIndentation() {
        CheckoutCode.Edit edit = CheckoutCode.addMember(checkout, REL,
            "public boolean isEmpty() {\n    return entries.isEmpty();\n}");

        assertThat(edit.answer()).startsWith("added isEmpty to Ledger in " + REL);
        assertThat(edit.content()).endsWith("""
                public int size() {
                    return entries.size();
                }

                public boolean isEmpty() {
                    return entries.isEmpty();
                }
            }
            """);
    }

    @Test
    void whatDoesNotCloseOrIsNotThereChangesNothing() {
        assertThat(CheckoutCode.replaceMember(checkout, "Ledger#size",
            "public int size() {\n    return 1;").answer()).contains("braces do not close");
        assertThat(CheckoutCode.replaceMember(checkout, "Ledger#total", "int total() {}").answer())
            .contains("declares no `total`", "append, size", "add_member");
        assertThat(CheckoutCode.replaceMember(checkout, "Basket#size", "int size() {}").answer())
            .contains("no file for `Basket`");
        assertThat(CheckoutCode.replaceMember(checkout, "../../outside/Ledger.java#size",
            "int size() {}").changed()).as("nothing outside the checkout is read").isFalse();
    }

    @Test
    void aMemberOfTheCheckoutIsReadAsItIsNowAndATypeItDoesNotHoldIsLeftToTheTree()
            throws Exception {
        Files.writeString(file, Files.readString(file).replace("return entries.size();",
            "return 42;"));

        assertThat(CheckoutCode.bodyOf(checkout, "Ledger#size"))
            .startsWith("// " + REL + ":").contains("return 42;");
        assertThat(CheckoutCode.bodyOf(checkout, "com.shop.server.Ledger"))
            .contains("public class Ledger {");
        assertThat(CheckoutCode.bodyOf(checkout, "FrameworkType#open")).isNull();
    }

    /**
     * Live run 89: the change left two helpers unused. With no way to delete a member, both
     * workers replaced them with a stub, read the whole file and wrote a diff.
     */
    @Test
    void oneMemberIsRemovedByNameWithItsCommentAndTheAnswerSaysWhoStillUsesIt() throws Exception {
        CheckoutCode.Edit edit = CheckoutCode.removeMember(checkout, "Ledger#size");

        assertThat(edit.changed()).isTrue();
        assertThat(edit.answer()).startsWith("removed size from " + REL)
            .contains("No other member of the file names it");
        assertThat(edit.content()).doesNotContain("size()")
            .endsWith("            append(entry);\n        }\n    }\n}\n");

        CheckoutCode.Edit field = CheckoutCode.removeMember(checkout, REL + "#entries");
        assertThat(field.answer()).contains("Still named", "append (line 11)", "size (line 21)");
        assertThat(field.content()).doesNotContain("new ArrayList<>()");

        CheckoutCode.Edit documented = CheckoutCode.removeMember(checkout, "Ledger#append:11");
        assertThat(documented.content()).doesNotContain("Appends one entry")
            .contains("public void append(String entry, int times)")
            .contains("new ArrayList<>();\n\n    public void append(String entry, int times)");

        assertThat(CheckoutCode.removeMember(checkout, "Ledger#append").answer())
            .contains("2 overloads", "Ledger#append:<line>");
        assertThat(CheckoutCode.removeMember(checkout, "Ledger#total").changed()).isFalse();
        assertThat(CheckoutCode.replaceMember(checkout, "Ledger#size", " ").answer())
            .contains("remove_member");
    }
}
