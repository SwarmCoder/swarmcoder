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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The library jars a checkout compiles against, as far as this machine holds them offline: the
 * same list the syntax tree is parsed with ({@link LstReader#classpathFor}), without the
 * checkout's own compiled folders. Read from the build files and the local repository; nothing is
 * run and nothing is downloaded. What the Java language server is given as its libraries.
 */
public final class ProjectClasspath {

    private ProjectClasspath() {}

    /** Never null and never throws; empty when nothing could be resolved. */
    public static List<Path> jarsOf(Path checkout) {
        if (checkout == null || !Files.isDirectory(checkout)) {
            return List.of();
        }
        try {
            return LstReader.classpathFor(checkout).stream().filter(Files::isRegularFile).toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
