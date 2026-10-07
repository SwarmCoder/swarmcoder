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
package com.swarmcoder.runtime;

import ai.koog.prompt.message.Message;
import ai.koog.prompt.message.MessagePart;
import ai.koog.prompt.message.RequestMetaInfo;
import ai.koog.prompt.message.ResponseMetaInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness run 79 (2026-10-04): the planner made 100 turns and 308 lookups and handed in no plan.
 * Its fixed opening was about as large as the mark its room is compacted to, so each of 14
 * compactions emptied every file it had read, and it read the same twelve files again each time.
 */
class ACutLeavesTheSessionWhatItWorksFromTest {

    private static String body(String name) {
        return "package com.example;\n\nclass " + name + " {\n"
            + ("    // a line of " + name + "\n").repeat(75) + "}\n";
    }

    private static void turn(List<Message> messages, String id, String path) {
        messages.add(new Message.Assistant(List.of(new MessagePart.Tool.Call(id, "read_file",
            "{\"path\":\"" + path + "\"}")), ResponseMetaInfo.Companion.getEmpty(), null, null,
            null));
        messages.add(new Message.User(List.of(new MessagePart.Tool.Result(id, "read_file",
            body(path), false)), RequestMetaInfo.Companion.getEmpty(), null));
    }

    /** A system prompt and an opening of {@code openingTokens}, then the turns. */
    private static List<Message> opened(int openingTokens) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.System(List.of(new MessagePart.Text("You are the planner.", null)),
            RequestMetaInfo.Companion.getEmpty(), null));
        messages.add(new Message.User(List.of(new MessagePart.Text("x".repeat(openingTokens * 4),
            null)), RequestMetaInfo.Companion.getEmpty(), null));
        return messages;
    }

    private static String outputOf(List<Message> messages, String id) {
        for (Message message : messages) {
            for (MessagePart part : message.getParts()) {
                if (part instanceof MessagePart.Tool.Result result && id.equals(result.getId())) {
                    return result.getOutput();
                }
            }
        }
        throw new AssertionError("no result of " + id);
    }

    @Test
    void theMarkACompactionCutsToIsNeverAtOrUnderWhatCannotBeCut() {
        HistoryTrim.Budget room = HistoryTrim.Budget.forWorkingContext(32_768);

        assertThat(HistoryTrim.lowWaterAbove(14_000, room))
            .as("above the fixed part, and still well under the mark it compacts at")
            .isEqualTo(14_000 + (room.highWaterTokens() - 14_000) / 2);
        assertThat(HistoryTrim.lowWaterAbove(4_000, room))
            .as("a small fixed part, a worker's, is cut to the configured mark as before")
            .isEqualTo(room.lowWaterTokens());
        assertThat(HistoryTrim.lowWaterAbove(30_000, room))
            .as("a fixed part over the room is the caller's stop, not a new mark")
            .isEqualTo(room.lowWaterTokens());
    }

    @Test
    void aCompactionInARoomMostlyTakenByTheOpeningKeepsTheNewerReadsWhole() {
        List<Message> history = opened(8_000);
        for (int i = 0; i < 30; i++) {
            turn(history, "call-" + i, "src/Thing" + i + ".java");
        }
        HistoryTrim.Budget room = HistoryTrim.Budget.forWorkingContext(32_768);
        assertThat(HistoryTrim.estimateTokens(history)).isGreaterThan(room.highWaterTokens());

        HistoryTrim.Result result = HistoryTrim.trim(history, room);

        assertThat(result.changed()).isTrue();
        assertThat(result.tokensAfter()).isLessThan(room.highWaterTokens());
        assertThat(result.removed()).as("emptying the oldest was enough").isZero();
        assertThat(outputOf(result.messages(), "call-0")).startsWith(HistoryTrim.ELIDED_MARK);
        assertThat(outputOf(result.messages(), "call-20"))
            .as("a read older than the last four turns is still whole: before, the cut went "
                + "to 13,107 tokens, which kept four of them")
            .isEqualTo(body("src/Thing20.java"));
    }

    @Test
    void whatTheSessionAskedForASecondTimeIsTheLastThingCut() {
        List<Message> history = opened(8_000);
        turn(history, "first", "src/Core.java");
        turn(history, "again", "src/Core.java");
        for (int i = 0; i < 28; i++) {
            turn(history, "call-" + i, "src/Thing" + i + ".java");
        }

        HistoryTrim.Result compacted = HistoryTrim.trim(history,
            HistoryTrim.Budget.forWorkingContext(32_768));
        assertThat(outputOf(compacted.messages(), "first")).startsWith(HistoryTrim.ELIDED_MARK);
        assertThat(outputOf(compacted.messages(), "call-0")).startsWith(HistoryTrim.ELIDED_MARK);
        assertThat(outputOf(compacted.messages(), "again"))
            .as("the second read of a file is the session saying it works from it")
            .isEqualTo(body("src/Core.java"));

        HistoryTrim.Result tidied = HistoryTrim.tidy(history, 8_192, 4_096, 600);
        assertThat(outputOf(tidied.messages(), "call-0")).startsWith(HistoryTrim.ELIDED_MARK);
        assertThat(outputOf(tidied.messages(), "again")).isEqualTo(body("src/Core.java"));
    }
}
