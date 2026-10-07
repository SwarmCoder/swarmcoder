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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A worker's old {@code write_file} and {@code apply_diff} arguments - the whole file bodies it
 * sent - are not resent in full on every later turn: {@link HistoryTrim#tidy} keeps the call, its
 * id and its path, and replaces the body by a short digest. The last turns stay whole.
 */
class OldWriteArgumentsAreTrimmedLikeOldResultsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String body(int index) {
        return "package com.example.thing" + index + ";\n\npublic class Thing" + index + " {\n"
            + ("    int field = " + index + "; // a line of the file\n").repeat(120) + "}\n";
    }

    private static String writeArgs(int index) throws Exception {
        return JSON.writeValueAsString(java.util.Map.of("path", "src/main/java/Thing" + index
            + ".java", "content", body(index)));
    }

    private static String diffArgs(int index) throws Exception {
        String diff = "--- a/src/main/java/Edit" + index + ".java\n+++ b/src/main/java/Edit" + index
            + ".java\n@@ -1,1 +1,121 @@\n" + ("+    int added = 1; // a line\n").repeat(120);
        return JSON.writeValueAsString(java.util.Map.of("unifiedDiff", diff));
    }

    private static List<Message> conversation(int turns) throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.System(List.of(new MessagePart.Text("You are a worker.", null)),
            RequestMetaInfo.Companion.getEmpty(), null));
        messages.add(new Message.User(List.of(new MessagePart.Text("Begin.", null)),
            RequestMetaInfo.Companion.getEmpty(), null));
        for (int i = 0; i < turns; i++) {
            boolean diff = i % 2 == 1;
            messages.add(new Message.Assistant(List.of(new MessagePart.Tool.Call("call-" + i,
                diff ? "apply_diff" : "write_file", diff ? diffArgs(i) : writeArgs(i))),
                ResponseMetaInfo.Companion.getEmpty(), null, null, null));
            messages.add(new Message.User(List.of(new MessagePart.Tool.Result("call-" + i,
                diff ? "apply_diff" : "write_file", "wrote it", false)),
                RequestMetaInfo.Companion.getEmpty(), null));
        }
        return messages;
    }

    private static MessagePart.Tool.Call callAt(List<Message> messages, int index) {
        for (MessagePart part : messages.get(index).getParts()) {
            if (part instanceof MessagePart.Tool.Call call) {
                return call;
            }
        }
        throw new AssertionError("no tool call at " + index);
    }

    @Test
    void oldFileBodiesBecomeAPathAndADigestAndTheLastTurnsStayWhole() throws Exception {
        List<Message> history = conversation(12);
        int before = HistoryTrim.estimateTokens(history);

        HistoryTrim.Result result = HistoryTrim.tidy(history, 4_000, 1_000, 600);

        assertThat(result.changed()).isTrue();
        assertThat(result.tokensAfter()).as("every later turn resends far less")
            .isLessThan(before / 2);

        MessagePart.Tool.Call oldestWrite = callAt(result.messages(), 2);
        assertThat(oldestWrite.getId()).isEqualTo("call-0");
        JsonNode kept = JSON.readTree(oldestWrite.getArgs());
        assertThat(kept.path("path").asText()).isEqualTo("src/main/java/Thing0.java");
        assertThat(kept.path("content").asText())
            .startsWith(HistoryTrim.ELIDED_MARK)
            .contains("are in the file now")
            .contains("package com.example.thing0;")
            .hasSizeLessThan(400);

        MessagePart.Tool.Call oldestDiff = callAt(result.messages(), 4);
        JsonNode keptDiff = JSON.readTree(oldestDiff.getArgs());
        assertThat(keptDiff.path("unifiedDiff").asText())
            .startsWith(HistoryTrim.ELIDED_MARK)
            .contains("src/main/java/Edit1.java").hasSizeLessThan(400);

        int last = result.messages().size() - 2;
        assertThat(callAt(result.messages(), last).getArgs())
            .as("the newest call is whole").isEqualTo(callAt(history, last).getArgs());
    }

    @Test
    void aShortSessionAndShortArgumentsAreLeftAlone() throws Exception {
        List<Message> history = conversation(3);

        HistoryTrim.Result result = HistoryTrim.tidy(history, 40_000, 1_000, 600);

        assertThat(result.changed()).isFalse();
        assertThat(result.messages()).isSameAs(history);
        assertThat(HistoryTrim.digestArguments("write_file", "{\"path\":\"a\",\"content\":\"x\"}"))
            .startsWith("{\"path\":\"a\"");
    }

    @Test
    void aSecondTidyDoesNotTrimWhatIsAlreadyTrimmed() throws Exception {
        HistoryTrim.Result first = HistoryTrim.tidy(conversation(12), 4_000, 1_000, 600);

        HistoryTrim.Result second = HistoryTrim.tidy(first.messages(), 4_000, 1_000, 600);

        assertThat(second.changed()).isFalse();
    }
}
