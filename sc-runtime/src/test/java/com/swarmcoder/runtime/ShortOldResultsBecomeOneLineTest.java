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
 * An old lookup result that is too short to keep first lines of does not stay whole for ever
 * (section 94).
 *
 * <p>Build of 2026-10-13, planning roles on a paid server: of the architect's 199 lookups, 98
 * returned up to 1,000 characters - a type's members, a build declaration, a document section -
 * and {@link HistoryTrim#tidy} left every one whole, because a digest of one would be no shorter.
 * Together they were 46,282 characters, sent again on every call, and they counted toward the
 * mark, so every tidy had to cut every longer result to get anywhere near it.
 */
class ShortOldResultsBecomeOneLineTest {

    /** About 800 characters: longer than a line naming the call, too short for first lines. */
    private static String members(int index) {
        return "public interface Thing" + index + " {\n"
            + ("    java.util.List<String> namesOfThing" + index + "(String filter);\n").repeat(13)
            + "}\n";
    }

    private static List<Message> conversation(int turns) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.System(List.of(new MessagePart.Text("You are the reviewer.", null)),
            RequestMetaInfo.Companion.getEmpty(), null));
        messages.add(new Message.User(List.of(new MessagePart.Text("The text to read.", null)),
            RequestMetaInfo.Companion.getEmpty(), null));
        for (int i = 0; i < turns; i++) {
            messages.add(new Message.Assistant(List.of(new MessagePart.Tool.Call("call-" + i,
                "public_shape", "{\"type\":\"com.example.Thing" + i + "\"}")),
                ResponseMetaInfo.Companion.getEmpty(), null, null, null));
            messages.add(new Message.User(List.of(new MessagePart.Tool.Result("call-" + i,
                "public_shape", members(i), false)), RequestMetaInfo.Companion.getEmpty(), null));
        }
        return messages;
    }

    private static String outputAt(List<Message> messages, int index) {
        for (MessagePart part : messages.get(index).getParts()) {
            if (part instanceof MessagePart.Tool.Result result) {
                return result.getOutput();
            }
        }
        throw new AssertionError("no tool result at " + index);
    }

    @Test
    void manyShortResultsAreCutToOneLineEachOldestFirstAndTheMarkIsReached() {
        List<Message> history = conversation(60);
        assertThat(members(0).length()).isBetween(HistoryTrim.ONE_LINE_NOT_WORTH_IT + 1, 1_000);
        int before = HistoryTrim.estimateTokens(history);

        HistoryTrim.Result result = HistoryTrim.tidy(history, 6_000, 1_500, 600);

        assertThat(result.changed()).as("before, nothing here could be shortened").isTrue();
        String oldest = outputAt(result.messages(), 3);
        assertThat(oldest).startsWith(HistoryTrim.ELIDED_MARK)
            .contains("public_shape").contains("com.example.Thing0")
            .contains(members(0).length() + " characters were returned")
            .contains("Make the same call again")
            .doesNotContain("namesOfThing0");
        assertThat(outputAt(result.messages(), history.size() - 1))
            .as("the last turns are whole").isEqualTo(members(59));
        assertThat(result.tokensAfter()).isLessThan(before / 2);

        long whole = 0;
        int tail = result.messages().size() - HistoryTrim.PROTECTED_TAIL_MESSAGES;
        for (int i = 3; i < tail; i += 2) {
            String output = outputAt(result.messages(), i);
            whole += output.startsWith(HistoryTrim.ELIDED_MARK) ? 0 : output.length() / 4;
        }
        // The 1,500 are counted in what cutting saves, and a short result's line is a quarter
        // of it: a little more than 1,500 tokens of them stay whole.
        assertThat(whole).as("about the 1,500 tokens asked for stay whole, the newest of them")
            .isBetween(1_500L, 2_400L);

        assertThat(HistoryTrim.tidy(result.messages(), 6_000, 1_500, 600).changed())
            .as("and the next call does not tidy again").isFalse();
    }

    @Test
    void aResultNoLongerThanTheLineThatWouldNameItIsLeftAlone() {
        List<Message> history = new ArrayList<>(conversation(60));
        history.set(3, new Message.User(List.of(new MessagePart.Tool.Result("call-0",
            "public_shape", "public interface Thing0 { }", false)),
            RequestMetaInfo.Companion.getEmpty(), null));

        HistoryTrim.Result result = HistoryTrim.tidy(history, 6_000, 1_500, 600);

        assertThat(outputAt(result.messages(), 3)).isEqualTo("public interface Thing0 { }");
    }
}
