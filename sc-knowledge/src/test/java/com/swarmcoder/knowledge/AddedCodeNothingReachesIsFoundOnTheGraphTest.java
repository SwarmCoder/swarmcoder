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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production code a run adds and nothing can reach is found from the object graph (the seven
 * accepted stories whose screens no user could open, 2026-10-05).
 *
 * <p>An invented shop on an invented framework, so that nothing here can pass because of a name
 * the implementation knows: pages are found by an annotation the project declares itself, a
 * plug-in by a service-loader file, and one module is entered through {@code main}. No model is
 * called and nothing outside this test's {@code @TempDir} is written.
 */
class AddedCodeNothingReachesIsFoundOnTheGraphTest {

    private static final String WEB = "shop-web/src/main/java/com/shop/web/";
    private static final String CORE = "shop-core/src/main/java/com/shop/core/";

    @TempDir
    Path tree;

    @BeforeEach
    void theShopAsItWasBeforeTheRun() throws Exception {
        write("pom.xml", "<project><modules><module>shop-core</module>"
            + "<module>shop-web</module></modules></project>");
        write("shop-core/pom.xml", "<project><artifactId>shop-core</artifactId></project>");
        write("shop-web/pom.xml", "<project><artifactId>shop-web</artifactId></project>");
        write(CORE + "Page.java", """
            package com.shop.core;
            public @interface Page { String value(); }
            """);
        write(CORE + "Plugin.java", """
            package com.shop.core;
            public interface Plugin { int price(int base); }
            """);
        write(CORE + "Catalog.java", """
            package com.shop.core;
            import java.util.ServiceLoader;
            public class Catalog {
                public int priceOf(int base) {
                    int price = base;
                    for (Plugin plugin : ServiceLoader.load(Plugin.class)) {
                        price = plugin.price(price);
                    }
                    return price;
                }
            }
            """);
        write(WEB + "Launcher.java", """
            package com.shop.web;
            public class Launcher {
                public static void main(String[] args) { }
            }
            """);
        write(WEB + "HomePage.java", """
            package com.shop.web;
            import com.shop.core.Page;
            @Page("/")
            public class HomePage {
                public String render() { return "home"; }
            }
            """);
        write(WEB + "CartPage.java", """
            package com.shop.web;
            import com.shop.core.Page;
            @Page("/cart")
            public class CartPage {
                public String render() { return new CartPanel().show(); }
            }
            """);
        write(WEB + "CartPanel.java", """
            package com.shop.web;
            import com.shop.core.Catalog;
            public class CartPanel {
                public String show() { return "total " + new Catalog().priceOf(3); }
            }
            """);
        write(WEB + "HolidayPrices.java", """
            package com.shop.web;
            import com.shop.core.Plugin;
            public class HolidayPrices implements Plugin {
                public int price(int base) { return base - 1; }
            }
            """);
        write("shop-web/src/main/resources/META-INF/services/com.shop.core.Plugin",
            "com.shop.web.HolidayPrices\n");
    }

    /** What the run adds: a panel only its test uses, a row only the panel uses, and a page. */
    private void theRunAddsAWishlist() throws Exception {
        write(WEB + "WishlistPanel.java", """
            package com.shop.web;
            public class WishlistPanel {
                public String show() { return new WishlistRow().text(); }
            }
            """);
        write(WEB + "WishlistRow.java", """
            package com.shop.web;
            public class WishlistRow {
                public String text() { return "row"; }
            }
            """);
        write(WEB + "OrdersPage.java", """
            package com.shop.web;
            import com.shop.core.Page;
            @Page("/orders")
            public class OrdersPage {
                // WishlistPanel is named here in a comment, which is not a use
                public String render() { return "orders, not WishlistPanel"; }
            }
            """);
        write("shop-web/src/test/java/com/shop/web/WishlistPanelTest.java", """
            package com.shop.web;
            class WishlistPanelTest {
                void shows() { new WishlistPanel().show(); }
            }
            """);
    }

    private static final Set<String> ADDED = Set.of(WEB + "WishlistPanel.java",
        WEB + "WishlistRow.java", WEB + "OrdersPage.java");

    @Test
    void aClassOnlyItsTestUsesIsAnOrphanAndAnAnnotatedPageIsNot() throws Exception {
        theRunAddsAWishlist();

        ReachableCode.Graph graph = ReachableCode.of(tree);
        ReachableCode.Finding finding = graph.judge(ADDED::contains);

        assertThat(graph.determined()).as(graph.note()).isTrue();
        assertThat(finding.status()).isEqualTo(ReachableCode.Status.UNREACHABLE);
        assertThat(finding.orphans()).extracting(ReachableCode.Orphan::file)
            .containsExactly(WEB + "WishlistPanel.java", WEB + "WishlistRow.java");
        assertThat(finding.orphans().get(0).usedByNothing()).isTrue();
        assertThat(finding.orphans().get(1).usedByNothing())
            .as("the row is used, by the panel nothing reaches").isFalse();
        assertThat(finding.discovered()).as("learned from the pages that were there")
            .containsExactly("@Page");
        assertThat(graph.entryPointFiles()).contains(WEB + "Launcher.java",
            WEB + "HomePage.java", WEB + "CartPage.java", WEB + "HolidayPrices.java");

        assertThat(finding.only(file -> file.endsWith("WishlistRow.java")).orphans())
            .as("a candidate answers for the files it added itself")
            .extracting(ReachableCode.Orphan::file).containsExactly(WEB + "WishlistRow.java");
        assertThat(finding.only(file -> false).status())
            .isEqualTo(ReachableCode.Status.ALL_REACHABLE);
        assertThat(graph.whyReached(WEB + "CartPanel.java"))
            .isEqualTo("reached: used by " + WEB + "CartPage.java");

        String objection = ReachableCode.objection(finding, "the candidate adds");
        assertThat(objection)
            .startsWith("the candidate adds production code that nothing in the application "
                + "can reach: WishlistPanel (" + WEB + "WishlistPanel.java), WishlistRow ("
                + WEB + "WishlistRow.java, used only by other unreachable code)")
            .contains("found by the framework (@Page)")
            .contains("say so in your report");
    }

    @Test
    void usedFromAPageTheUserArrivesAtEverythingIsReachable() throws Exception {
        theRunAddsAWishlist();
        write(WEB + "HomePage.java", """
            package com.shop.web;
            import com.shop.core.Page;
            @Page("/")
            public class HomePage {
                public String render() { return "home " + new WishlistPanel().show(); }
            }
            """);

        ReachableCode.Finding finding = ReachableCode.of(tree).judge(ADDED::contains);

        assertThat(finding.status()).isEqualTo(ReachableCode.Status.ALL_REACHABLE);
        assertThat(ReachableCode.objection(finding, "this run adds")).isNull();
    }

    @Test
    void aTypeNamedInAResourceFileIsAnEntryPointAndOneNamedInADocumentIsNot() throws Exception {
        write(WEB + "SummerPrices.java", """
            package com.shop.web;
            import com.shop.core.Plugin;
            public class SummerPrices implements Plugin {
                public int price(int base) { return base; }
            }
            """);
        write(WEB + "WinterPrices.java", """
            package com.shop.web;
            import com.shop.core.Plugin;
            public class WinterPrices implements Plugin {
                public int price(int base) { return base; }
            }
            """);
        write("shop-web/src/main/resources/META-INF/services/com.shop.core.Plugin",
            "com.shop.web.HolidayPrices\ncom.shop.web.SummerPrices\n");
        write("docs/prices.md", "See com.shop.web.WinterPrices.\n");

        ReachableCode.Finding finding = ReachableCode.of(tree).judge(
            Set.of(WEB + "SummerPrices.java", WEB + "WinterPrices.java")::contains);

        assertThat(finding.orphans()).extracting(ReachableCode.Orphan::file)
            .containsExactly(WEB + "WinterPrices.java");
    }

    @Test
    void aTreeWithNoEntryPointAndALibrarysSurfaceAreNotJudged() throws Exception {
        // A module of helpers nothing in the project uses: its public surface is for callers
        // outside, and a new helper there is not an orphan.
        String util = "shop-util/src/main/java/com/shop/util/";
        for (String name : List.of("Dates", "Money", "Names")) {
            write(util + name + ".java",
                "package com.shop.util;\npublic class " + name + " { }\n");
        }
        ReachableCode.Finding surface = ReachableCode.of(tree).judge(
            Set.of(util + "Names.java")::contains);
        assertThat(surface.status()).isEqualTo(ReachableCode.Status.ALL_REACHABLE);
        assertThat(surface.note()).contains("shop-util/src/main/java/");

        Path bare = tree.resolve("elsewhere");
        Files.createDirectories(bare.resolve("src/main/java/a"));
        Files.writeString(bare.resolve("src/main/java/a/One.java"),
            "package a;\npublic class One { }\n");
        Files.writeString(bare.resolve("src/main/java/a/Two.java"),
            "package a;\npublic class Two { One one; }\n");
        ReachableCode.Finding none = ReachableCode.of(bare).judge(
            Set.of("src/main/java/a/Two.java")::contains);
        assertThat(none.status()).isEqualTo(ReachableCode.Status.UNDETERMINED);
        assertThat(none.note()).contains("no entry point");
        assertThat(ReachableCode.objection(none, "this run adds")).isNull();
    }

    @Test
    void aPlanThatOnlyAddsFilesWhereNothingReachesThemIsObjectedTo() throws Exception {
        ReachableCode.Graph graph = ReachableCode.of(tree);
        java.util.function.Predicate<String> exists =
            path -> Files.isRegularFile(tree.resolve(path));
        ReachableCode.PlannedTask core = new ReachableCode.PlannedTask("Wishlist in the catalog",
            Set.of(CORE + "Wishlist.java", CORE + "Catalog.java", "shop-core/pom.xml"),
            "Add Wishlist and let Catalog return it.");
        ReachableCode.PlannedTask panelOnly = new ReachableCode.PlannedTask("Wishlist panel",
            Set.of(WEB + "WishlistPanel.java", "shop-web/pom.xml"),
            "Add WishlistPanel showing the wishlist.");

        String objection = ReachableCode.planObjection(graph, List.of(core, panelOnly), exists);

        assertThat(objection)
            .as("the catalog is reachable but nothing of the catalog's module uses the web "
                + "module, so the panel still has nowhere to be opened from")
            .startsWith("the plan adds new production source files (" + WEB
                + "WishlistPanel.java) and no task may change a file the application already "
                + "reaches")
            .doesNotContain("Wishlist.java,")
            .contains(WEB + "HomePage.java")
            .contains("@Page");

        ReachableCode.PlannedTask panelAndPage = new ReachableCode.PlannedTask("Wishlist panel",
            Set.of(WEB + "WishlistPanel.java", WEB + "HomePage.java"),
            "Add WishlistPanel and show it on HomePage.");
        assertThat(ReachableCode.planObjection(graph, List.of(core, panelAndPage), exists))
            .isNull();

        ReachableCode.PlannedTask aNewPage = new ReachableCode.PlannedTask("Wishlist page",
            Set.of(WEB + "WishlistPage.java"), "Add WishlistPage, annotated @Page(\"/wishlist\").");
        assertThat(ReachableCode.planObjection(graph, List.of(aNewPage), exists)).isNull();

        ReachableCode.PlannedTask aNewModule = new ReachableCode.PlannedTask("Reports",
            Set.of("shop-reports/src/main/java/com/shop/reports/Report.java"), "Add Report.");
        assertThat(ReachableCode.planObjection(graph, List.of(aNewModule), exists))
            .as("nothing is known about a module that does not exist yet").isNull();
    }

    private void write(String path, String text) throws Exception {
        Path file = tree.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }
}
