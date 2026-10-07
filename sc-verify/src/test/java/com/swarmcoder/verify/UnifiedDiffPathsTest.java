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
package com.swarmcoder.verify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The changed-file list the reachability gate is fed. It comes from the candidate's own
 * {@code git diff --cached}, not from its task's write set, so a worker that wandered outside the
 * plan is caught by the same gate as a plan that pointed nowhere.
 */
class UnifiedDiffPathsTest {

    @Test
    void readsAddedAndModifiedFiles() {
        String diff = """
            diff --git a/src/main/java/com/example/A.java b/src/main/java/com/example/A.java
            new file mode 100644
            index 0000000..1111111
            --- /dev/null
            +++ b/src/main/java/com/example/A.java
            @@ -0,0 +1,2 @@
            +package com.example;
            +class A {}
            diff --git a/mod/pom.xml b/mod/pom.xml
            index 2222222..3333333 100644
            --- a/mod/pom.xml
            +++ b/mod/pom.xml
            @@ -1,1 +1,1 @@
            -<a/>
            +<b/>
            """;

        assertThat(UnifiedDiffPaths.addedOrChanged(diff))
            .containsExactlyInAnyOrder("src/main/java/com/example/A.java", "mod/pom.xml");
    }

    @Test
    void deletionsAreNotChangedFiles() {
        String diff = """
            diff --git a/old/Gone.java b/old/Gone.java
            deleted file mode 100644
            index 4444444..0000000
            --- a/old/Gone.java
            +++ /dev/null
            @@ -1,1 +0,0 @@
            -class Gone {}
            """;

        assertThat(UnifiedDiffPaths.addedOrChanged(diff)).isEmpty();
    }

    @Test
    void aRenameIsReportedAtItsDestination() {
        String diff = """
            diff --git a/old/A.java b/new/A.java
            similarity index 100%
            rename from old/A.java
            rename to new/A.java
            """;

        assertThat(UnifiedDiffPaths.addedOrChanged(diff)).containsExactly("new/A.java");
    }

    @Test
    void pathsWithSpacesSurvive() {
        String diff = """
            diff --git a/some dir/A file.java b/some dir/A file.java
            index 1..2 100644
            --- a/some dir/A file.java
            +++ b/some dir/A file.java
            """;

        assertThat(UnifiedDiffPaths.addedOrChanged(diff)).containsExactly("some dir/A file.java");
    }

    @Test
    void nothingParseableMeansNothingKnown() {
        assertThat(UnifiedDiffPaths.addedOrChanged(null)).isEmpty();
        assertThat(UnifiedDiffPaths.addedOrChanged("")).isEmpty();
        assertThat(UnifiedDiffPaths.addedOrChanged("not a diff at all")).isEmpty();
    }
}
