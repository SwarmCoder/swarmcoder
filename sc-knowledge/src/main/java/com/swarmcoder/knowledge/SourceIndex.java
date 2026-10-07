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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Every Java file under the knowledge roots, by simple name and by address — so a question that
 * names a type ("what is on ClickEvent?") reaches that type's own file, not the file that happens
 * to mention it most.
 *
 * <p>Why this is not the curator's inventory. {@link KnowledgeCurator} keeps at most 400 source
 * files per root, taken in path order, and the reference folder this was measured on has 742:
 * everything from {@code zerozstack-ui-components} onward — the UI component classes workers ask
 * about most — was past the cap and unreachable by any query. This index holds names only, which
 * costs nothing, so it holds all of them.
 */
final class SourceIndex {

    private static final Logger log = LoggerFactory.getLogger(SourceIndex.class);
    private static final long TTL_MS = 60_000;

    /** One file: {@code address} is {@code <rootLabel>/<relative path>}, the form workers quote. */
    record Hit(String address, Path file, boolean test) {}

    private final List<KnowledgeCurator.Root> roots;
    private volatile Map<String, List<Hit>> byName;
    private volatile List<Hit> all;
    private volatile long loadedAt;

    SourceIndex(List<KnowledgeCurator.Root> roots) {
        this.roots = List.copyOf(roots);
    }

    /** Files whose simple name is {@code name}, case-insensitively; main sources before tests. */
    List<Hit> byName(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (key.endsWith(".java")) {
            key = key.substring(0, key.length() - 5);
        }
        return index().getOrDefault(key, List.of());
    }

    /** The file at exactly {@code address}, or whose address ends with it. */
    Optional<Hit> byAddress(String address) {
        String wanted = address.replace('\\', '/');
        index();
        Optional<Hit> exact = all.stream().filter(h -> h.address().equals(wanted)).findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        return all.stream().filter(h -> h.address().endsWith("/" + wanted)).findFirst();
    }

    int size() {
        index();
        return all.size();
    }

    private Map<String, List<Hit>> index() {
        Map<String, List<Hit>> cached = byName;
        if (cached != null && System.currentTimeMillis() - loadedAt < TTL_MS) {
            return cached;
        }
        synchronized (this) {
            if (byName != null && System.currentTimeMillis() - loadedAt < TTL_MS) {
                return byName;
            }
            List<Hit> hits = new ArrayList<>();
            for (KnowledgeCurator.Root root : roots) {
                if (root.path() == null || !Files.isDirectory(root.path())) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(root.path())) {
                    walk.filter(Files::isRegularFile)
                        .filter(f -> {
                            String name = f.toString().replace('\\', '/');
                            return name.endsWith(".java") && !name.contains("/target/")
                                && !name.contains("/.git/") && !name.contains("/node_modules/");
                        })
                        .sorted()
                        .forEach(f -> {
                            String relative = root.path().relativize(f).toString().replace('\\', '/');
                            hits.add(new Hit(root.label() + "/" + relative, f,
                                relative.contains("/src/test/") || relative.contains("/test/")));
                        });
                } catch (Exception e) {
                    log.warn("Source index walk failed for {}: {}", root.path(), e.getMessage());
                }
            }
            Map<String, List<Hit>> map = new HashMap<>();
            for (Hit hit : hits) {
                String file = hit.file().getFileName().toString();
                String key = file.substring(0, file.length() - 5).toLowerCase(Locale.ROOT);
                map.computeIfAbsent(key, k -> new ArrayList<>()).add(hit);
            }
            for (List<Hit> list : map.values()) {
                list.sort(Comparator.comparing(Hit::test)
                    .thenComparingInt(h -> h.address().length())
                    .thenComparing(Hit::address));
            }
            all = hits;
            byName = map;
            loadedAt = System.currentTimeMillis();
            return map;
        }
    }
}
