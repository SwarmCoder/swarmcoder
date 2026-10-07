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

import com.swarmcoder.lsp.JdtLanguageServer;
import com.swarmcoder.lsp.JdtLsInstall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The whole chain a role's question takes, on a real checkout: the offline classpath, a real JDT
 * Language Server, and {@link LanguageQueries}. Skipped unless a JDT LS is installed AND the
 * environment names a checkout to read:
 *
 * <pre>
 * SWARMCODER_LIVE_PROJECT   a Maven checkout (it is only read)
 * SWARMCODER_LIVE_TYPE      a type of that project, simple or full name
 * SWARMCODER_LIVE_LIBRARY   a type that exists only in one of its library jars, full name
 * </pre>
 *
 * It calls no model and reaches no network. Prints what it measured on lines starting
 * {@code JDTLS-REAL}.
 */
class LanguageServerOnARealProjectLiveTest {

    @Test
    void aRolesQuestionsAreAnsweredOnARealCheckout(@TempDir Path scratch) throws Exception {
        String named = System.getenv("SWARMCODER_LIVE_PROJECT");
        Path home = JdtLsInstall.locate(null);
        assumeTrue(named != null && !named.isBlank() && home != null, "no checkout named, or no "
            + "JDT LS installed (run with -Dswarmcoder.jdtLs=on)");
        Path project = Path.of(named);
        String type = System.getenv().getOrDefault("SWARMCODER_LIVE_TYPE", "");
        String library = System.getenv().getOrDefault("SWARMCODER_LIVE_LIBRARY", "");
        TreeSet<String> before = topLevel(project);

        long began = System.nanoTime();
        List<Path> jars = ProjectClasspath.jarsOf(project);
        System.out.println("JDTLS-REAL classpath: " + jars.size() + " jar(s) in " + since(began)
            + " ms");

        KnowledgeCurator curator = new KnowledgeCurator(
            List.of(new KnowledgeCurator.Root("project", project, "local")), null,
            scratch.resolve("cache"));
        try (JdtLanguageServer server = new JdtLanguageServer(home, project,
                scratch.resolve("data"), jars, false)) {
            curator.languageServer(server, project);
            LanguageQueries queries = LanguageQueries.of(curator);

            String first = timed("find_symbol (first query, with the start)",
                () -> queries.findSymbol(type.isBlank() ? "*Test" : type));
            System.out.println("JDTLS-REAL server start " + server.coldStartMillis() + " ms, pid "
                + server.pid());
            assertThat(server.isAvailable()).isTrue();
            assertThat(first).contains("type(s) match").contains("project/");

            if (!library.isBlank()) {
                String members = timed("members of a library type",
                    () -> queries.membersOrNull(library));
                assertThat(members).contains("library jar");
            }
            if (!type.isBlank()) {
                assertThat(timed("usages", () -> queries.usagesOrNull(type)))
                    .contains("reference(s) to");
                timed("implementations", () -> queries.implementationsOrNull(type));
                timed("supertypes", () -> queries.supertypesOf(type));
                timed("doc", () -> queries.docOf(type));
            }
        }
        assertThat(topLevel(project)).as("the server wrote nothing into the checkout")
            .isEqualTo(before);
    }

    private static TreeSet<String> topLevel(Path project) throws Exception {
        try (Stream<Path> list = Files.list(project)) {
            return new TreeSet<>(list.map(p -> p.getFileName().toString()).toList());
        }
    }

    private static long since(long began) {
        return (System.nanoTime() - began) / 1_000_000;
    }

    private static String timed(String what, Supplier<String> query) {
        long began = System.nanoTime();
        String answer = query.get();
        String text = String.valueOf(answer);
        System.out.println("JDTLS-REAL " + what + ": " + since(began) + " ms, " + text.length()
            + " chars\n" + (text.length() > 1_500 ? text.substring(0, 1_500) + "..." : text));
        return answer;
    }
}
