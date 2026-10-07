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
package com.swarmcoder.lsp;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the Eclipse JDT Language Server product is on this machine (2026-10-04).
 *
 * <p>Three places, the first that holds a product wins: the system property
 * {@value #PROPERTY}; the product setting {@code tools.jdtLsHome}; the newest version folder under
 * {@code ~/.swarmcoder/tools/jdtls}, which is where it is installed by default
 * ({@code ~/.swarmcoder/tools/jdtls/1.61.0}). A product is a folder with a {@code plugins} folder
 * in it.
 */
public final class JdtLsInstall {

    public static final String PROPERTY = "swarmcoder.jdtLsHome";

    private JdtLsInstall() {}

    private static volatile String configured;

    /** The product's {@code tools.jdtLsHome} setting, told once when the settings are read. */
    public static void configure(String jdtLsHome) {
        configured = jdtLsHome;
    }

    /** The installed product's home by the three places above, or null when none is installed. */
    public static Path locate() {
        return locate(configured);
    }

    /**
     * {@code -Dswarmcoder.jdtLs=off}: act as if none were installed. The build's unit tests run
     * with it, so no test's outcome depends on what this machine happens to have installed.
     */
    public static final String SWITCH = "swarmcoder.jdtLs";

    /** {@code ~/.swarmcoder/tools/jdtls}: one folder per installed version. */
    public static Path defaultToolsDir() {
        return Path.of(System.getProperty("user.home"), ".swarmcoder", "tools", "jdtls");
    }

    /**
     * @param configured the product's {@code tools.jdtLsHome} setting; null or blank is "not set"
     * @return the product's home, or null when none is installed
     */
    public static Path locate(String configured) {
        if ("off".equalsIgnoreCase(System.getProperty(SWITCH, "").strip())) {
            return null;
        }
        return locate(System.getProperty(PROPERTY), configured, defaultToolsDir());
    }

    static Path locate(String property, String configured, Path toolsDir) {
        for (String named : new String[] {property, configured}) {
            if (named != null && !named.isBlank()) {
                Path home = Path.of(named.strip());
                // A setting that names a folder is the answer even when the folder is wrong:
                // the server then says why it is not available instead of silently using another.
                return home;
            }
        }
        if (toolsDir == null || !Files.isDirectory(toolsDir)) {
            return null;
        }
        if (isProduct(toolsDir)) {
            return toolsDir;
        }
        Path newest = null;
        try (DirectoryStream<Path> versions = Files.newDirectoryStream(toolsDir)) {
            for (Path version : versions) {
                if (isProduct(version) && (newest == null || compare(version, newest) > 0)) {
                    newest = version;
                }
            }
        } catch (Exception e) {                                                // noqa
            return null;
        }
        return newest;
    }

    private static boolean isProduct(Path dir) {
        return Files.isDirectory(dir.resolve("plugins"));
    }

    /** Version folders by their numbers: 1.61.0 is newer than 1.9.0. */
    private static int compare(Path a, Path b) {
        List<Integer> x = numbers(a.getFileName().toString());
        List<Integer> y = numbers(b.getFileName().toString());
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            int left = i < x.size() ? x.get(i) : 0;
            int right = i < y.size() ? y.get(i) : 0;
            if (left != right) {
                return Integer.compare(left, right);
            }
        }
        return a.getFileName().toString().compareTo(b.getFileName().toString());
    }

    private static List<Integer> numbers(String name) {
        List<Integer> out = new ArrayList<>();
        for (String part : name.split("[^0-9]+")) {
            if (!part.isEmpty() && part.length() < 9) {
                out.add(Integer.parseInt(part));
            }
        }
        return out;
    }
}
