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
 * A session that looks things up does not resend every file it ever read on every later turn.
 *
 * <p>Harness run 66 (2026-10-02): the expert's model was sent 989,781 prompt tokens to write
 * 24,531 - about 20,000 a call over 49 calls - because each turn resends the whole conversation
 * and nothing was ever taken out of it below the model's full room. {@link HistoryTrim#tidy}
 * replaces the lookup results the session has moved on from by their first lines, in batches, and
 * never touches the last few turns.
 */
class OldLookupResultsAreReplacedByTheirFirstLinesTest {

    private static String fileBody(int index) {
        return "package com.example.thing" + index + ";\n\nimport java.util.List;\n\n"
            + ("    // line of file " + index + " that nobody needs twice\n").repeat(200);
    }

    private static List<Message> conversation(int turns) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.System(List.of(new MessagePart.Text("You are the expert.", null)),
            RequestMetaInfo.Companion.getEmpty(), null));
        messages.add(new Message.User(List.of(new MessagePart.Text("The question.", null)),
            RequestMetaInfo.Companion.getEmpty(), null));
        for (int i = 0; i < turns; i++) {
            messages.add(new Message.Assistant(List.of(new MessagePart.Tool.Call("call-" + i,
                "read_file", "{\"path\":\"ref/src/Thing" + i + ".java\"}")),
                ResponseMetaInfo.Companion.getEmpty(), null, null, null));
            messages.add(new Message.User(List.of(new MessagePart.Tool.Result("call-" + i,
                "read_file", fileBody(i), false)), RequestMetaInfo.Companion.getEmpty(), null));
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
    void aShortSessionIsLeftExactlyAlone() {
        List<Message> history = conversation(4);

        HistoryTrim.Result result = HistoryTrim.tidy(history, 6_000, 1_500, 600);

        assertThat(result.changed()).isFalse();
        assertThat(result.messages()).isSameAs(history);
    }

    @Test
    void theOldestResultsBecomeTheirFirstLinesAndTheLastTurnsStayWhole() {
        List<Message> history = conversation(12);
        int before = HistoryTrim.estimateTokens(history);

        HistoryTrim.Result result = HistoryTrim.tidy(history, 6_000, 1_500, 600);

        assertThat(result.changed()).isTrue();
        assertThat(result.tokensAfter())
            .as("what every later turn resends is a fraction of what it was")
            .isLessThan(before / 2);
        String oldest = outputAt(result.messages(), 3);
        assertThat(oldest)
            .as("the oldest read is now a digest: it says what was read and how to get it back")
            .startsWith(HistoryTrim.ELIDED_MARK)
            .contains("read_file").contains("Thing0.java")
            .contains("Make the same call again")
            .as("and keeps the head of the file - its package and imports")
            .contains("package com.example.thing0;").contains("import java.util.List;");
        assertThat(oldest.length()).isLessThan(1_200);

        int last = result.messages().size() - 1;
        assertThat(outputAt(result.messages(), last))
            .as("the turns the session is still working from are untouched")
            .isEqualTo(fileBody(11));
        assertThat(outputAt(result.messages(), last - 6)).isEqualTo(fileBody(8));
        assertThat(result.messages()).hasSameSizeAs(history);
        assertThat(result.removed()).as("nothing is removed; this is not a compaction").isZero();
    }

    @Test
    void aTidiedSessionIsNotTidiedAgainOnTheNextTurn() {
        HistoryTrim.Result first = HistoryTrim.tidy(conversation(12), 6_000, 1_500, 600);
        List<Message> next = new ArrayList<>(first.messages());
        next.add(new Message.Assistant(List.of(new MessagePart.Tool.Call("call-12", "read_file",
            "{\"path\":\"ref/src/Thing12.java\"}")), ResponseMetaInfo.Companion.getEmpty(), null,
            null, null));
        next.add(new Message.User(List.of(new MessagePart.Tool.Result("call-12", "read_file",
            fileBody(12), false)), RequestMetaInfo.Companion.getEmpty(), null));

        HistoryTrim.Result second = HistoryTrim.tidy(next, 6_000, 1_500, 600);

        assertThat(second.changed())
            .as("each rewrite costs the model server its cached prefix, so it happens in "
                + "batches: one result aging out of the last turns is not enough to do it again")
            .isFalse();
    }

    @Test
    void aSessionThatAskedForNoTidyingIsNeverTouched() {
        List<Message> history = conversation(12);
        assertThat(HistoryTrim.tidy(history, 0, 0, 0).messages()).isSameAs(history);
    }

    @Test
    void aResultNoLongerThanItsDigestIsLeftAsItIs() {
        MessagePart.Tool.Result small = new MessagePart.Tool.Result("call-1", "public_shape",
            "class com.example.Small\n  public void go()", false);
        assertThat(HistoryTrim.digest(small, "{\"type\":\"Small\"}", 600))
            .isEqualTo(small.getOutput());
    }

    /**
     * A worker reads a file, edits it, reads it again: the first read is superseded, and it is the
     * cheapest thing in the conversation to cut - cheaper than an older result nothing replaced.
     */
    @Test
    void aResultTheSessionHasSinceReplacedGoesBeforeAnOlderOneItHasNot() {
        List<Message> history = conversation(6);
        // Turn 6 writes the file turn 1 read; turn 7 makes turn 2's call again, word for word.
        history.add(new Message.Assistant(List.of(new MessagePart.Tool.Call("call-6", "write_file",
            "{\"path\":\"ref/src/Thing1.java\",\"content\":\"class Thing1 {}\"}")),
            ResponseMetaInfo.Companion.getEmpty(), null, null, null));
        history.add(new Message.User(List.of(new MessagePart.Tool.Result("call-6", "write_file",
            "ok: wrote ref/src/Thing1.java", false)), RequestMetaInfo.Companion.getEmpty(), null));
        history.add(new Message.Assistant(List.of(new MessagePart.Tool.Call("call-7", "read_file",
            "{\"path\":\"ref/src/Thing2.java\"}")),
            ResponseMetaInfo.Companion.getEmpty(), null, null, null));
        history.add(new Message.User(List.of(new MessagePart.Tool.Result("call-7", "read_file",
            fileBody(2), false)), RequestMetaInfo.Companion.getEmpty(), null));

        assertThat(HistoryTrim.supersededCalls(history)).containsExactlyInAnyOrder("call-1", "call-2");

        // Four results (turns 0 to 3) are outside the last four turns: about 10,000 tokens. Leaving
        // 5,500 of them whole means cutting two - and the two cut are the superseded ones.
        HistoryTrim.Result result = HistoryTrim.tidy(history, 6_000, 5_500, 600);

        assertThat(result.dropped()).isEqualTo(2);
        assertThat(outputAt(result.messages(), 3)).as("the oldest read: nothing replaced it")
            .isEqualTo(fileBody(0));
        assertThat(outputAt(result.messages(), 5)).as("read, then written since")
            .startsWith(HistoryTrim.ELIDED_MARK).contains("Thing1.java");
        assertThat(outputAt(result.messages(), 7)).as("read, then read again")
            .startsWith(HistoryTrim.ELIDED_MARK).contains("Thing2.java");
        assertThat(outputAt(result.messages(), 9)).isEqualTo(fileBody(3));
    }

    /**
     * Live run 93 (2026-10-08): the first lines kept of 134 lookups were about 20,000 tokens
     * themselves, resent on every call, and no later tidy could shorten them.
     */
    @Test
    void theFirstLinesAnEarlierTidyKeptBecomeOneLineAtTheNext() {
        HistoryTrim.Result first = HistoryTrim.tidy(conversation(12), 6_000, 1_500, 600);
        List<Message> later = new ArrayList<>(first.messages());
        for (int i = 12; i < 16; i++) {
            later.add(new Message.Assistant(List.of(new MessagePart.Tool.Call("call-" + i,
                "read_file", "{\"path\":\"ref/src/Thing" + i + ".java\"}")),
                ResponseMetaInfo.Companion.getEmpty(), null, null, null));
            later.add(new Message.User(List.of(new MessagePart.Tool.Result("call-" + i,
                "read_file", fileBody(i), false)), RequestMetaInfo.Companion.getEmpty(), null));
        }
        assertThat(outputAt(later, 3)).as("after the first tidy: its first lines")
            .contains("package com.example.thing0;");

        HistoryTrim.Result second = HistoryTrim.tidy(later, 6_000, 1_500, 600);

        assertThat(second.changed()).isTrue();
        String oldest = outputAt(second.messages(), 3);
        assertThat(oldest)
            .as("one line: the call it was, how much it returned, how to get it back")
            .startsWith(HistoryTrim.ELIDED_MARK)
            .contains("read_file").contains("Thing0.java")
            .contains("none of it is kept (" + fileBody(0).length() + " characters were returned)")
            .contains("Make the same call again")
            .doesNotContain("package com.example.thing0;").doesNotContain("\n");
        assertThat(oldest.length()).isLessThan(300);
        assertThat(outputAt(second.messages(), 19))
            .as("a result that was whole until now keeps its first lines")
            .startsWith(HistoryTrim.ELIDED_MARK).contains("package com.example.thing8;");
        int last = second.messages().size() - 1;
        assertThat(outputAt(second.messages(), last)).isEqualTo(fileBody(15));
        assertThat(HistoryTrim.collapse(oldest)).as("one line is not shortened again").isNull();
        assertThat(HistoryTrim.collapse("some file text")).isNull();
    }
}
