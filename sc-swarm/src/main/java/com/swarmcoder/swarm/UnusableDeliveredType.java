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

import com.swarmcoder.knowledge.JavaSourceFacts;
import com.swarmcoder.verify.UnifiedDiffPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The cheap check a task with no acceptance test of its own gets before its winner can merge: did
 * it deliver a class that no other class can use?
 *
 * <p>Harness run 65 (2026-10-02): the text-constants class of an early task was a final class with
 * a private constructor and instance methods only. The task claimed no check, so "it compiles" was
 * all that stood between that class and the tasks planned to build on it; two waves later a task
 * could not follow the rule that told it to use the class, and the rule was reworded for the whole
 * project (see {@link SiblingDefects} for the cure; this is the prevention).
 *
 * <p>A source scan of the files the candidate itself added or changed — no model, no compiler, no
 * knowledge of any library. Only main code is read: a test class is not something another task
 * builds on. The shape reported is unusable in any program ({@link
 * JavaSourceFacts#unusableFromOutside}); everything it cannot settle, it lets through.
 */
final class UnusableDeliveredType {

    private UnusableDeliveredType() {}

    /**
     * Null when nothing the candidate wrote is unusable, or when nothing could be read; otherwise
     * the sentence that fails it, naming each class and what would make it usable.
     */
    static String in(Path workspace, String unifiedDiff) {
        if (workspace == null) {
            return null;
        }
        List<String> found = new ArrayList<>();
        for (String path : UnifiedDiffPaths.addedOrChanged(unifiedDiff)) {
            String normal = path.replace('\\', '/');
            if (!normal.endsWith(".java") || normal.contains("/test/") || normal.startsWith("test/")) {
                continue;
            }
            String file = normal.substring(normal.lastIndexOf('/') + 1);
            String typeName = file.substring(0, file.length() - ".java".length());
            try {
                Path source = workspace.resolve(normal);
                if (!Files.isRegularFile(source)) {
                    continue;
                }
                String why = JavaSourceFacts.of(Files.readString(source)).unusableFromOutside(typeName);
                if (why != null) {
                    found.add(normal + ": " + why);
                }
            } catch (Exception e) {
                // A file that will not read or parse settles nothing, and nothing is never a fail.
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        return "this task has no acceptance test of its own, so what it delivers was checked for "
            + "being usable by the tasks that build on it, and it is not:\n  - "
            + String.join("\n  - ", found)
            + "\nMake its members static, or give it a constructor or a static factory other "
            + "classes can call.";
    }
}
