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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 69: a journey that expects to see a text is asked about it only when the project as it
 * stands does not hold that text. "Holds" is the string literals of shipped Java code - what
 * {@code texts_of} returns - and the page and message files; not a comment, and not the story's
 * own tests.
 */
class WhetherTheProjectHoldsATextIsAskedWithNoModelTest {

    @TempDir
    Path tree;

    @Test
    void aLiteralOfShippedCodeAndAPageFileAreHeldACommentAndATestAreNot() throws Exception {
        write("shop-client/src/main/java/com/shop/OrdersScreen.java", """
            package com.shop;
            // the Dune saga is only mentioned here
            public class OrdersScreen {
                String title() { return "Orders of the day"; }
            }
            """);
        write("shop-client/src/main/resources/index.html", "<h1>Welcome back</h1>");
        write("shop-client/src/test/java/swarm/accept/OrdersTest.java", """
            package swarm.accept;
            class OrdersTest { String expected = "A Wizard of Earthsea"; }
            """);
        write("shop-client/target/classes/generated.js", "var t = 'Compiled label';");

        Predicate<String> held = ProjectTexts.heldIn(tree);

        assertThat(held.test("orders of the DAY")).as("a literal, whatever the case").isTrue();
        assertThat(held.test("Orders")).as("part of a literal").isTrue();
        assertThat(held.test("Welcome back")).as("a page file").isTrue();
        assertThat(held.test("Dune")).as("a comment is not a text").isFalse();
        assertThat(held.test("A Wizard of Earthsea")).as("the story's own test").isFalse();
        assertThat(held.test("Compiled label")).as("build output").isFalse();
        assertThat(held.test(" ")).isFalse();
    }

    @Test
    void aTreeThatIsNotThereGivesNoAnswerAtAll() {
        assertThat(ProjectTexts.heldIn(tree.resolve("gone"))).isNull();
        assertThat(ProjectTexts.heldIn(null)).isNull();
    }

    private void write(String relative, String content) throws Exception {
        Path file = tree.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
