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

import com.swarmcoder.domain.DesignFinding;
import com.swarmcoder.domain.Task;
import com.swarmcoder.runtime.PathPolicy;
import com.swarmcoder.runtime.PromptBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 114, finding 3: the opening of a worker is 44% of all worker input. What another
 * task builds goes by title (the selection itself is {@code ArchitectHandover}'s, tested in
 * sc-workflow); SwarmCoder's own locked files are not sent to a project that has none of them;
 * a line said three times is sent once. Measured on a test project shaped like the audited one.
 */
class AWorkersOpeningCarriesOnlyWhatIsItsOwnTest {

    @TempDir
    Path project;

    private static final String NEEDS = "The test in server/src/test/java needs org.junit.jupiter "
        + "on the classpath of server: declare it in server/pom.xml.";

    private static DesignFinding finding(String about, String source, int lines) {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            code.append("        component.add(new Field(\"field").append(i)
                .append("\")); // a line of the example\n");
        }
        return new DesignFinding(UUID.randomUUID(), about, source,
            "How the example does this part.", code.toString().stripTrailing());
    }

    private Task task(List<DesignFinding> findings) {
        Task task = new Task();
        task.setId(UUID.fromString("00000000-0000-0000-0000-000000000003"));
        task.setTitle("server endpoints");
        task.setInstructions("Write the server's endpoints.\n" + NEEDS + "\n\n" + NEEDS + "\n\n"
            + NEEDS + "\n}\n}\n");
        task.setWriteSet(Set.of("server/pom.xml", "server/src/main/java/app/Api.java"));
        task.setArchitectFindings(findings);
        return task;
    }

    private static int size(PromptBundle bundle) {
        int chars = 0;
        for (PromptBundle.Segment segment : bundle.segments()) {
            chars += segment.content() == null ? 0 : segment.content().length();
        }
        return chars;
    }

    @Test
    void theOpeningLosesWhatIsAnotherTasksAndWhatTheProjectDoesNotHave() throws Exception {
        Files.createDirectories(project.resolve("server/src/main/java/app"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        List<String> locked = new ArrayList<>(PathPolicy.TRUST_KERNEL);
        locked.add("pom.xml");
        locked.add("server/");

        // As the audited build sent it: three findings about the client and shared types,
        // 8.1K characters, whole.
        List<DesignFinding> own = List.of(finding("Api", "body_of Api#list", 12));
        List<DesignFinding> others = List.of(
            finding("", "body_of example/client/src/main/java/app/BookScreen.java#build", 40),
            finding("", "body_of example/client/src/main/java/app/BookForm.java#form", 35),
            finding("", "shape_of example/shared/src/main/java/app/Book.java", 35));
        List<DesignFinding> whole = new ArrayList<>(own);
        whole.addAll(others);
        List<DesignFinding> byTitle = new ArrayList<>(own);
        others.forEach(f -> byTitle.add(f.withoutSnippet()));

        Task before = task(whole);
        PromptBundle opening = SwarmDispatcher.buildBundle(before, null, "", "", locked, "");
        String text = opening.segments().stream().map(PromptBundle.Segment::content)
            .reduce("", String::concat);
        // The line said three times is sent once; the short code lines stay.
        assertThat(text.split(java.util.regex.Pattern.quote(NEEDS), -1)).hasSize(2);
        assertThat(text).contains("}\n}");

        List<String> sent = SwarmDispatcher.lockedPathsOfTheProject(locked, before, project);
        assertThat(sent).containsExactly("pom.xml", "server/");
        assertThat(SwarmDispatcher.lockedPathsOfTheProject(locked, before, null))
            .as("no folder to look in: the list as it was").isEqualTo(locked);

        int wasSent = size(SwarmDispatcher.buildBundle(before, null, "", "", locked, ""))
            + 2 * (NEEDS.length() + 2);
        int nowSent = size(SwarmDispatcher.buildBundle(task(byTitle), null, "", "", sent, ""));
        System.out.println("[section 114] worker opening on the test project: " + wasSent
            + " characters before, " + nowSent + " after (findings about other tasks "
            + DesignFinding.sizeOf(others) + " -> "
            + DesignFinding.sizeOf(byTitle.subList(1, byTitle.size())) + ", locked list "
            + locked.toString().length() + " -> " + sent.toString().length()
            + ", repeated line " + 3 * NEEDS.length() + " -> " + NEEDS.length() + ")");
        assertThat(nowSent).isLessThan(wasSent - 7_000);
    }
}
