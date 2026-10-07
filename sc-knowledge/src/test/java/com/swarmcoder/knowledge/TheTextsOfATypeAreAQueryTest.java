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
 * Section 63: the test author writes a journey through screens that exist, and needs what they
 * show - a link's label, a route's name, a button's text. Those are string literals. The tree
 * returns them by member, without the code around them, so no screen is read whole for them
 * (CLAUDE.md section 1: a missing query is a bug in the tools, not a reason to read files).
 */
class TheTextsOfATypeAreAQueryTest {

    private static final String SCREEN = """
        package com.shop.client.screen;

        // "a comment is not a text"
        @Route("orders")
        public class OrdersScreen extends Screen {
            private static final String TITLE = "Orders";
            private static final char QUOTE = '"';

            /** "nor is javadoc" */
            public OrdersScreen() {
                add(new Button("Add order", click -> open(new OrderForm())));
                add(new Link("Back to \\"home\\"", "home"));
            }

            void empty() {
                show(Texts.NOTHING_YET);
            }

            static class OrderForm {
                String label() { return "Customer"; }
            }
        }
        """;

    @Test
    void everyLiteralIsListedByTheMemberItIsWrittenInAndNoCodeComesWithIt() {
        String texts = TreeQueries.textsIn("project/shop-client/OrdersScreen.java", SCREEN,
            "com.shop.client.screen.OrdersScreen");

        assertThat(texts)
            .contains("Texts in `OrdersScreen` (project/shop-client/OrdersScreen.java:")
            .contains("OrdersScreen (the type's own annotations): \"orders\"")
            .contains("TITLE :")
            .contains("\"Orders\"")
            .contains("OrdersScreen() :")
            .contains("\"Add order\", \"Back to \\\"home\\\"\", \"home\"")
            .contains("OrderForm.label() :")
            .contains("\"Customer\"")
            .contains("ask texts_of for that type");
        assertThat(texts)
            .as("comments, a quote character and code are not texts")
            .doesNotContain("a comment is not a text")
            .doesNotContain("nor is javadoc")
            .doesNotContain("click -> open")
            .doesNotContain("empty()");
        assertThat(texts.length()).isLessThan(SCREEN.length());
    }

    @Test
    void aTypeWithNoLiteralSaysSoAndATypeThatIsNotThereIsNotInvented() {
        assertThat(TreeQueries.textsIn("project/Plain.java",
            "package p;\npublic class Plain {\n    int size() { return 1; }\n}\n", "p.Plain"))
            .contains("holds no string literal");
        assertThat(TreeQueries.textsIn("project/Plain.java",
            "package p;\npublic class Plain {\n}\n", "p.Other"))
            .contains("does not declare `Other`");
    }

    @Test
    void theQueryCountsAsATreeQueryAndTheJourneyCheckAsItsOwnKind() {
        assertThat(ExpertTools.kindOf("texts_of", "OrdersScreen"))
            .isEqualTo(com.swarmcoder.inference.LookupMeter.Kind.TREE);
        assertThat(ExpertTools.kindOf("check_journey", "x.journey.yaml"))
            .isEqualTo(com.swarmcoder.inference.LookupMeter.Kind.JOURNEY_CHECK);
        assertThat(com.swarmcoder.inference.LookupMeter.Kind.JOURNEY_CHECK.structured()).isTrue();
    }
}
