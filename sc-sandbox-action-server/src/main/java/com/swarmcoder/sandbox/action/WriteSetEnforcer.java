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
package com.swarmcoder.sandbox.action;

import java.nio.file.Path;
import java.util.Set;

public class WriteSetEnforcer {
    public static boolean isAllowed(Path targetPath, Path workspace, Set<String> writeSet) {
        if (!targetPath.startsWith(workspace)) {
            return false;
        }
        if (writeSet.contains("*")) {
            return true;
        }
        String relative = workspace.relativize(targetPath).toString().replace('\\', '/');
        for (String pattern : writeSet) {
            if (pattern.endsWith("/") && relative.startsWith(pattern)) {
                return true;
            } else if (relative.equals(pattern)) {
                return true;
            }
        }
        return false;
    }
}