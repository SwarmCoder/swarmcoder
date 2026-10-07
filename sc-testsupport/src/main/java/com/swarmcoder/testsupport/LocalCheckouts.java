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
package com.swarmcoder.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Finds a folder a test wants from this machine without naming a drive or a user.
 *
 * <p>Some tests measure against a real checkout — the ZeroZ Stack sources as reference
 * documentation, the bookshelf demo as a project — and skip, loudly, when it is absent. Where that
 * checkout sits differs per machine, so no test writes an absolute path. Each names the folder
 * relative to wherever people keep their checkouts, and this looks for it beside the working
 * directory and beside each folder above it. A worktree three levels down finds the same folder a
 * plain clone does.
 */
public final class LocalCheckouts {

    private LocalCheckouts() {
    }

    /**
     * The first of {@code relatives} that exists under the working directory or any folder above
     * it, nearest first. When none exists, the first one resolved beside the working directory:
     * a path that is not there, so the caller's own "is it on this machine" check answers no.
     */
    public static Path find(String... relatives) {
        Path start = Path.of("").toAbsolutePath().normalize();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            for (String relative : relatives) {
                Path candidate = dir.resolve(relative);
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        }
        Path parent = start.getParent() == null ? start : start.getParent();
        return parent.resolve(relatives[0]);
    }
}
