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
 * Section 95. A result a session asked for a second time was left whole by every tidy and never
 * counted. In a room of a million tokens no compaction ever cut it either: one architect's
 * fifteen such results were 74,214 characters on every later call. With a bound they stay whole
 * newest first up to the bound; a result asked for a third time always stays, so the bound
 * cannot start the reading-again the rule was made against.
 */
class ResultsAskedForTwiceAreBoundedTest {

    private static final String BIG = "line of a file\n".repeat(400); // 6,000 characters

    private static Message call(String id, String path) {
        return new Message.Assistant(List.of(new MessagePart.Tool.Call(id, "read_file",
            "{\"path\":\"" + path + "\"}")), ResponseMetaInfo.Companion.getEmpty(), null, null, null);
    }

    private static Message result(String id, String output) {
        return new Message.User(List.of(new MessagePart.Tool.Result(id, "read_file", output, false)),
            RequestMetaInfo.Companion.getEmpty(), null);
    }

    private static String outputOf(List<Message> messages, String id) {
        for (Message message : messages) {
            for (MessagePart part : message.getParts()) {
                if (part instanceof MessagePart.Tool.Result result && id.equals(result.getId())) {
                    return result.getOutput();
                }
            }
        }
        throw new AssertionError("no result " + id);
    }

    /** Three files read, cut, and read again; a fourth read three times; then a tail of calls. */
    private static List<Message> aConversation() {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.System(List.of(new MessagePart.Text("You design.", null)),
            RequestMetaInfo.Companion.getEmpty(), null));
        messages.add(new Message.User(List.of(new MessagePart.Text("Design it.", null)),
            RequestMetaInfo.Companion.getEmpty(), null));
        int n = 0;
        for (String path : List.of("A.java", "B.java", "C.java", "D.java", "D.java")) {
            String id = "first" + n++;
            messages.add(call(id, path));
            messages.add(result(id, "[dropped to save room] read earlier"));
        }
        for (String path : List.of("A.java", "B.java", "C.java", "D.java")) {
            String id = "again-" + path;
            messages.add(call(id, path));
            messages.add(result(id, BIG));
        }
        for (int i = 0; i < 6; i++) {
            messages.add(call("tail" + i, "T" + i + ".java"));
            messages.add(result("tail" + i, "short"));
        }
        return messages;
    }

    @Test
    void withNoBoundEveryResultAskedForAgainStaysWholeAsBefore() {
        HistoryTrim.Result tidied = HistoryTrim.tidy(aConversation(), 1_000, 500, 600);

        assertThat(tidied.changed()).as("nothing but asked-again results is old enough to cut")
            .isFalse();
    }

    @Test
    void withABoundTheNewestStayWholeAndTheOlderAreCutLikeAnyResult() {
        // 2,000 tokens: one result of 1,500 tokens fits, a second does not.
        HistoryTrim.Result tidied = HistoryTrim.tidy(aConversation(), 1_000, 500, 600, 2_000);

        assertThat(tidied.changed()).isTrue();
        assertThat(outputOf(tidied.messages(), "again-C.java")).as("the newest asked for twice")
            .isEqualTo(BIG);
        assertThat(outputOf(tidied.messages(), "again-A.java")).as("the oldest is cut")
            .startsWith("[dropped to save room]").hasSizeLessThan(1_200);
        assertThat(outputOf(tidied.messages(), "again-B.java"))
            .startsWith("[dropped to save room]");
    }

    @Test
    void aResultAskedForAThirdTimeAlwaysStaysWhole() {
        HistoryTrim.Result tidied = HistoryTrim.tidy(aConversation(), 1_000, 500, 600, 0);

        assertThat(outputOf(tidied.messages(), "again-D.java"))
            .as("its call is in the conversation three times: cutting it again would only "
                + "have it read a fourth time").isEqualTo(BIG);
        assertThat(outputOf(tidied.messages(), "again-C.java"))
            .startsWith("[dropped to save room]");
    }
}
