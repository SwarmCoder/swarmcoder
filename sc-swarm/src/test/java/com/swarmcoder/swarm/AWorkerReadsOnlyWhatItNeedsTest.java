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
package com.swarmcoder.swarm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker can read part of a file, and a long command output keeps both of its ends
 * (2026-10-02).
 *
 * <p>Harness run 66 sent the workers' model 53 prompt tokens for every token it wrote, because
 * every turn resends every earlier tool result - whole files read to look at one method, and
 * build logs cut after their first eight thousand characters with the errors further down.
 */
class AWorkerReadsOnlyWhatItNeedsTest {

    private static final String SOURCE = """
        package com.example;

        import java.util.List;

        /** Keeps the books. */
        public class BookStore {

            private final List<String> titles;

            BookStore(List<String> titles) {
                this.titles = titles;
            }

            /**
             * Saves one title.
             */
            @Deprecated
            public void save(String title) {
                if (title == null) {
                    throw new IllegalArgumentException("no title");
                }
                titles.add(title);
            }

            public int count() {
                save("x");
                return titles.size();
            }

            interface Listener {
                void changed(String title);
            }
        }
        """;

    @Test
    void aPlainPathIsStillTheWholeFileByteForByte() {
        ReadSlice.Request asked = ReadSlice.parse("src/BookStore.java");
        assertThat(asked.wholeFile()).isTrue();
        assertThat(ReadSlice.render(SOURCE, asked, 8_000)).isEqualTo(SOURCE);
    }

    @Test
    void aLineRangeIsThoseLinesAndSaysWhichTheyWere() {
        ReadSlice.Request asked = ReadSlice.parse("src/BookStore.java:10-12");
        assertThat(asked.path()).isEqualTo("src/BookStore.java");

        String part = ReadSlice.render(SOURCE, asked, 8_000);

        assertThat(part).startsWith("[src/BookStore.java: lines 10-12 of 33]\n")
            .contains("BookStore(List<String> titles) {")
            .contains("this.titles = titles;")
            .doesNotContain("public void save");
        assertThat(ReadSlice.render(SOURCE, ReadSlice.parse("src/BookStore.java:400"), 8_000))
            .startsWith("error:").contains("33 lines");
    }

    @Test
    void aNameIsThatOneMethodWithTheCommentAndAnnotationAboveIt() {
        String save = ReadSlice.render(SOURCE, ReadSlice.parse("src/BookStore.java#save"), 8_000);

        assertThat(save).startsWith("['save' in src/BookStore.java: lines 14-23 of 33]\n")
            .as("the declaration, not the call inside count()")
            .contains("* Saves one title.").contains("@Deprecated")
            .contains("public void save(String title) {").contains("titles.add(title);")
            .doesNotContain("public int count()");

        assertThat(ReadSlice.render(SOURCE, ReadSlice.parse("src/BookStore.java#Listener"), 8_000))
            .contains("interface Listener {").contains("void changed(String title);")
            .doesNotContain("public int count()");
        assertThat(ReadSlice.render(SOURCE, ReadSlice.parse("src/BookStore.java#changed"), 8_000))
            .as("a method with no body").contains("void changed(String title);")
            .contains("lines 31-31 of 33");
        assertThat(ReadSlice.render(SOURCE, ReadSlice.parse("src/BookStore.java#missing"), 8_000))
            .startsWith("error: no method or type named 'missing'")
            .contains("src/BookStore.java:FROM-TO");
    }

    @Test
    void aFileTooLongForOneReadEndsOnAWholeLineAndSaysHowToReadOn() {
        StringBuilder big = new StringBuilder();
        for (int i = 1; i <= 400; i++) {
            big.append("line number ").append(i).append(" of a long file\n");
        }

        String shown = ReadSlice.render(big.toString(), ReadSlice.parse("docs/long.md"), 2_000);

        assertThat(shown).startsWith("line number 1 of a long file\n");
        assertThat(shown.length()).isLessThan(2_400);
        assertThat(shown).contains("too long for one read")
            .containsPattern("\\[shown: lines 1-(\\d+) of 400")
            .containsPattern("Read on with docs/long\\.md:\\d+-\\d+")
            .contains("docs/long.md#name");
        String lastWholeLine = shown.substring(0, shown.indexOf("[shown:")).stripTrailing();
        assertThat(lastWholeLine).as("it stops at the end of a line").endsWith("of a long file");
    }

    @Test
    void aLongCommandOutputKeepsItsBeginningAndItsEnd() {
        StringBuilder log = new StringBuilder("[INFO] Scanning for projects...\n");
        for (int i = 0; i < 600; i++) {
            log.append("[INFO] compiling module part ").append(i).append('\n');
        }
        log.append("[ERROR] BookStore.java:[17,9] cannot find symbol\n[INFO] BUILD FAILURE\n");

        String shown = ReadSlice.headAndTail(log.toString(), 8_000);

        assertThat(shown.length()).isLessThan(8_600);
        assertThat(shown).startsWith("[INFO] Scanning for projects...")
            .as("the errors at the bottom of a build log are what the old cut threw away")
            .contains("cannot find symbol").endsWith("[INFO] BUILD FAILURE\n")
            .contains("characters of output are not shown here")
            .contains("run the command again");
        assertThat(ReadSlice.headAndTail("exit=0\nshort", 8_000)).isEqualTo("exit=0\nshort");
    }
}
