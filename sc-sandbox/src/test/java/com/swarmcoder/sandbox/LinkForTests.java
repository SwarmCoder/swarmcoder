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
package com.swarmcoder.sandbox;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Makes a directory link for a test: a symbolic link where the machine allows one, otherwise (on
 * Windows, where symbolic links need a privilege) a junction, which needs none and which
 * {@code toRealPath} resolves the same way.
 */
final class LinkForTests {

    private LinkForTests() {
    }

    /** @return false when neither kind of link could be made, so the test can be skipped */
    static boolean directoryLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (Exception symlinkRefused) {
            // fall through to a junction
        }
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return false;
        }
        try {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                link.toString(), target.toString()).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0 && Files.exists(link);
        } catch (Exception e) {
            return false;
        }
    }
}
