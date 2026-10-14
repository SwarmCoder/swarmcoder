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

import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.knowledge.Librarian;
import com.swarmcoder.runtime.PromptBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEVELOPER_CORRECTIONS section 113, finding 5: the steering notice told every worker "There is
 * no reference documentation for this project" beside an opening of 33 documents - its hint was
 * read from catalogue lines a brief no longer has - and the notice at 8 calls said less than the
 * one at 16, which was followed by a write.
 */
class TheFirstSteeringNoticeSaysWhatItKnowsTest {

    @TempDir
    Path worktree;

    @Test
    void theNoticeAtEightCallsNamesTheDocumentsTheFilesToWriteAndTheCounts() throws Exception {
        PromptBundle bundle = PromptBundle.builder().systemRole("worker")
            .knowledgeBrief("### Available libraries\n- org.example:store 2.4\n\n"
                + Librarian.DOCUMENTS_HEADING + "\n33 document(s). doc_outline with an empty "
                + "string lists every one with its sections.\n").build();
        String hint = WorkerLoop.referenceHint(bundle);
        assertThat(hint).contains("33 reference document(s)").contains("doc_search");

        Files.writeString(worktree.resolve("pom.xml"), "<project/>");
        Task task = new Task(UUID.randomUUID(), 1, "t", "i",
            Set.of("pom.xml", "server/src/main/java/App.java"), Set.of(), List.of(), null, null,
            null, new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.READY);
        WorkerToolbox toolbox = new WorkerToolbox(worktree, task);
        toolbox.setReferenceHint(hint);

        String result = "";
        for (int i = 0; i < 8; i++) {
            result = toolbox.read("pom.xml");
        }

        assertThat(result)
            .doesNotContain("There is no reference documentation")
            .contains("33 reference document(s)")
            .contains("Your task writes pom.xml, server/src/main/java/App.java (not there yet)")
            .contains("At 16 such calls in a row, reading pauses until you write; at 24 this "
                + "attempt is stopped");
        assertThat(result).as("a whole build file read names the lookup of one part of it")
            .contains("build_of ''#<part>").contains("offline_artifacts");
        assertThat(toolbox.read("pom.xml")).as("the notice refuses nothing")
            .doesNotContain("Reading is paused");
    }
}
