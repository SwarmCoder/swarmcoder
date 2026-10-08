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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * "Does the project, as it stands, hold this text?" - answered by SwarmCoder itself, with no
 * model and nothing put into any role's conversation (section 69).
 *
 * <p>It is asked about a text a journey expects to see: a text the shipped code already holds is
 * one the application can show without anybody typing it. The texts are the same ones
 * {@code texts_of} returns - the string literals of the shipped Java sources, comments left out
 * ({@link TreeQueries#literalsIn}) - and the whole content of the page and message files beside
 * them, where a text is not a literal of any type. Folders of tests and of build output are not
 * read: a text in the story's own acceptance test is not something the application shows.
 *
 * <p>Read once, on the first question, and only then.
 */
public final class ProjectTexts {

    private ProjectTexts() {}

    private static final Set<String> SKIPPED = Set.of(".git", "target", "build", "node_modules",
        ".swarmcoder", "dist", "out", "test", "tests", "it", "__tests__", "e2e");
    private static final Set<String> WHOLE = Set.of("html", "htm", "js", "mjs", "jsx", "ts",
        "tsx", "properties");
    private static final long MAX_FILE_BYTES = 512 * 1024;
    private static final long MAX_TOTAL_CHARS = 16L * 1024 * 1024;

    /**
     * @return whether a text is held by the shipped code under {@code tree}, whatever its case;
     *         null when the tree cannot be read, so that nothing is concluded from it
     */
    public static Predicate<String> heldIn(Path tree) {
        if (tree == null || !Files.isDirectory(tree)) {
            return null;
        }
        return new Predicate<>() {
            private List<String> texts;

            @Override
            public synchronized boolean test(String text) {
                if (texts == null) {
                    texts = read(tree);
                }
                String wanted = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
                return !wanted.isEmpty() && texts.stream().anyMatch(held -> held.contains(wanted));
            }
        };
    }

    private static List<String> read(Path tree) {
        List<String> texts = new ArrayList<>();
        long[] total = new long[1];
        try {
            Files.walkFileTree(tree, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(tree) && SKIPPED.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    String type = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
                    boolean java = type.equals("java");
                    if ((!java && !WHOLE.contains(type)) || attrs.size() > MAX_FILE_BYTES) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        String content = Files.readString(file);
                        if (java) {
                            for (String literal : TreeQueries.literalsIn(
                                    JavaOutline.withoutComments(content))) {
                                texts.add(literal.toLowerCase(Locale.ROOT));
                                total[0] += literal.length();
                            }
                        } else {
                            texts.add(content.toLowerCase(Locale.ROOT));
                            total[0] += content.length();
                        }
                    } catch (IOException | RuntimeException unreadable) {         // noqa
                        // not text: it holds none
                    }
                    return total[0] > MAX_TOTAL_CHARS ? FileVisitResult.TERMINATE
                        : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {                              // noqa
            // what was read stands
        }
        return texts;
    }
}
