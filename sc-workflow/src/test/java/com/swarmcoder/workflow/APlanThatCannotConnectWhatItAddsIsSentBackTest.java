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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import com.swarmcoder.knowledge.ReachableCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plan that writes only new source files, with no task allowed to change anything the
 * application already reaches, is objected to before any code is written (the seven accepted
 * stories whose screens no user could open, 2026-10-05). No model; the start tree's object graph.
 */
class APlanThatCannotConnectWhatItAddsIsSentBackTest {

    private static final String SRC = "app/src/main/java/com/desk/";

    @TempDir
    Path repo;

    @AfterEach
    void switchBackOn() {
        System.clearProperty(ReachableCode.SWITCH);
    }

    private ReachableCode.Graph theDesk() throws Exception {
        Files.createDirectories(repo.resolve(SRC));
        Files.writeString(repo.resolve(SRC + "Desk.java"), """
            package com.desk;
            public class Desk {
                public static void main(String[] args) { System.out.println(new Menu().text()); }
            }
            """);
        Files.writeString(repo.resolve(SRC + "Menu.java"), """
            package com.desk;
            public class Menu {
                public String text() { return "menu"; }
            }
            """);
        return ReachableCode.of(repo);
    }

    private static TaskGraph planWriting(String... paths) {
        Task task = new Task(UUID.randomUUID(), 1, "Report screen", "Add the report screen.",
            Set.of(paths), Set.of(), List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
        return new TaskGraph(UUID.randomUUID(), 1, null, List.of(task), List.of());
    }

    @Test
    void onlyNewFilesIsAnObjectionNamingThemAndWhereTheApplicationIsEntered() throws Exception {
        ReachableCode.Graph desk = theDesk();

        String objection = PlanConnectsWhatItAdds.objection(
            planWriting(SRC + "Report.java", "app/pom.xml"), desk, repo);

        assertThat(objection)
            .startsWith("the plan adds new production source files (" + SRC + "Report.java)")
            .contains("Put the existing file the new code is reached from into the write set")
            .contains(SRC + "Desk.java");
    }

    @Test
    void withAReachableFileToChangeOrTheCheckOffThereIsNone() throws Exception {
        ReachableCode.Graph desk = theDesk();

        assertThat(PlanConnectsWhatItAdds.objection(
            planWriting(SRC + "Report.java", SRC + "Menu.java"), desk, repo)).isNull();
        assertThat(PlanConnectsWhatItAdds.objection(
            planWriting(SRC + "Menu.java"), desk, repo)).isNull();

        System.setProperty(ReachableCode.SWITCH, "off");
        assertThat(PlanConnectsWhatItAdds.objection(
            planWriting(SRC + "Report.java"), desk, repo)).isNull();
    }
}
