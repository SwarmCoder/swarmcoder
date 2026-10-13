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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The result of an earlier run of a build, and an earlier draft of a file with the result it
 * got, are cut to a short note once a later call of the same tool for the same thing has an
 * answer; the latest of each stays whole (section 98). Which calls belong together is read from
 * the tool that was called and its arguments - {@link AgentRuntime.RunAgain} and
 * {@link AgentRuntime.Draft} on the tool's own method - and from nothing a call or a result
 * says.
 *
 * <p>First on the real agent runtime against a scripted server, where what the model server is
 * sent can be read; then the rule on its own.
 */
class WhatALaterCallReplacedIsCutToANoteTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DROPPED = HistoryTrim.ELIDED_MARK;

    public static final class Tools {
        private int builds;

        @AgentRuntime.RunAgain
        public String build(String command) {
            builds++;
            return "exit=" + (builds == 1 ? 1 : 0) + "\n"
                + ("line of the output of build " + builds + "\n").repeat(60);
        }

        public String write(String path, @AgentRuntime.Draft String content) {
            return "wrote " + path + "\n" + "a line the check said about it\n".repeat(40);
        }

        public String look(String what) {
            return ("about " + what + " ").repeat(120);
        }
    }

    private static List<AgentRuntime.ToolBinding> tools() throws Exception {
        Tools tools = new Tools();
        return List.of(
            new AgentRuntime.ToolBinding("build", "Builds.", tools,
                Tools.class.getMethod("build", String.class)),
            new AgentRuntime.ToolBinding("write", "Writes a file.", tools,
                Tools.class.getMethod("write", String.class, String.class)),
            new AgentRuntime.ToolBinding("look", "Looks one thing up.", tools,
                Tools.class.getMethod("look", String.class)));
    }

    private static String draft(String mark) {
        return ("// " + mark + " a line of the file\n").repeat(40);
    }

    private static ScriptedModel.Step writes(String path, String content) throws Exception {
        return ScriptedModel.Step.call("write",
            JSON.writeValueAsString(Map.of("path", path, "content", content)));
    }

    /** The arguments of call {@code id} as request {@code messages} carries them. */
    private static String argumentsOf(JsonNode messages, String id) {
        for (JsonNode message : messages) {
            for (JsonNode call : message.path("tool_calls")) {
                if (call.path("id").asText().equals(id)) {
                    return call.path("function").path("arguments").asText();
                }
            }
        }
        throw new AssertionError("no call " + id);
    }

    /** The result of call {@code id} as request {@code messages} carries it. */
    private static String resultOf(JsonNode messages, String id) {
        for (JsonNode message : messages) {
            if (message.path("tool_call_id").asText().equals(id)) {
                return message.path("content").asText();
            }
        }
        throw new AssertionError("no result of " + id);
    }

    @Test
    void anEarlierRunAndAnEarlierDraftAreNotSentAgainWhole() throws Exception {
        String build = "{\"command\":\"mvn -q test\"}";
        List<ScriptedModel.Step> script = List.of(
            ScriptedModel.Step.call("build", build),            // call_1
            writes("A.java", draft("FIRST-DRAFT")),             // call_2
            writes("B.java", draft("OTHER-FILE")),              // call_3
            writes("A.java", draft("SECOND-DRAFT")),            // call_4
            ScriptedModel.Step.call("build", build),            // call_5
            ScriptedModel.Step.call("look", "{\"what\":\"x\"}"),
            ScriptedModel.Step.says("Done."));
        try (ScriptedModel server = new ScriptedModel(script);
             AgentRuntime.AgentSession session = server.open(tools())) {
            assertThat(session.run("Begin.").completed()).isTrue();

            // Request 4 is sent before the second draft of A.java exists.
            assertThat(argumentsOf(server.messagesOf(4), "call_2")).contains("FIRST-DRAFT");

            JsonNode afterSecondDraft = server.messagesOf(5);
            String first = argumentsOf(afterSecondDraft, "call_2");
            assertThat(first).doesNotContain("FIRST-DRAFT").contains(DROPPED);
            assertThat(JSON.readTree(first).path("path").asText())
                .as("the call still says what it wrote").isEqualTo("A.java");
            assertThat(resultOf(afterSecondDraft, "call_2")).startsWith(DROPPED)
                .contains("It began: wrote A.java").hasSizeLessThan(400);
            assertThat(argumentsOf(afterSecondDraft, "call_3")).as("another file's draft")
                .contains("OTHER-FILE");
            assertThat(argumentsOf(afterSecondDraft, "call_4")).as("the latest draft is whole")
                .contains("SECOND-DRAFT").doesNotContain(DROPPED);
            assertThat(resultOf(afterSecondDraft, "call_4")).doesNotContain(DROPPED);
            assertThat(resultOf(afterSecondDraft, "call_1")).as("the build has not run again yet")
                .contains("line of the output of build 1");

            JsonNode afterSecondBuild = server.messagesOf(6);
            assertThat(resultOf(afterSecondBuild, "call_1")).startsWith(DROPPED)
                .as("the note keeps how the earlier run ended").contains("It began: exit=1")
                .doesNotContain("line of the output of build 1");
            assertThat(resultOf(afterSecondBuild, "call_5")).contains("exit=0")
                .doesNotContain(DROPPED).contains("line of the output of build 2");

            // What every request begins with is never touched.
            for (int turn = 2; turn <= server.requests.size(); turn++) {
                assertThat(server.messagesOf(turn).get(0)).isEqualTo(server.messagesOf(1).get(0));
                assertThat(server.messagesOf(turn).get(1)).isEqualTo(server.messagesOf(1).get(1));
            }
        }
    }

    private static Message.Assistant calls(String id, String tool, String args) {
        return new Message.Assistant(List.of(new MessagePart.Tool.Call(id, tool, args)),
            ResponseMetaInfo.Companion.getEmpty(), null, null, null);
    }

    private static Message.User answers(String id, String tool, String output) {
        return new Message.User(List.of(new MessagePart.Tool.Result(id, tool, output, false)),
            RequestMetaInfo.Companion.getEmpty(), null);
    }

    private static List<Message> opening() {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message.System(List.of(new MessagePart.Text("You do the work.", null)),
            RequestMetaInfo.Companion.getEmpty(), null));
        messages.add(new Message.User(List.of(new MessagePart.Text("Begin.", null)),
            RequestMetaInfo.Companion.getEmpty(), null));
        return messages;
    }

    @Test
    void aLaterCallAnsweredUnchangedReplacesNothing() throws Exception {
        String args = "{\"command\":\"mvn -q test\"}";
        String output = "exit=0\n" + "a line\n".repeat(200);
        List<Message> history = opening();
        history.add(calls("c1", "build", args));
        history.add(answers("c1", "build", output));
        history.add(calls("c2", "build", args));

        Superseded.Result same = Superseded.shorten(history, tools(), List.of(
            new Superseded.Now("c2", "build", args, HistoryTrim.UNCHANGED_MARK + ", from your "
                + "call 1 turn ago.")));
        assertThat(same.changed()).as("the earlier result is the one the line points back to")
            .isFalse();
        assertThat(same.messages()).isSameAs(history);

        Superseded.Result cut = Superseded.shorten(history, tools(), List.of(
            new Superseded.Now("c2", "build", args, "exit=1\n" + "another line\n".repeat(200))));
        assertThat(cut.results()).isEqualTo(1);
        assertThat(cut.tokensAfter()).isLessThan(cut.tokensBefore());
        assertThat(cut.messages().get(0)).isSameAs(history.get(0));
        assertThat(cut.messages().get(1)).isSameAs(history.get(1));
    }

    @Test
    void aToolThatDeclaresNothingAndACallWithOtherArgumentsAreLeftAlone() throws Exception {
        List<Message> history = opening();
        history.add(calls("c1", "look", "{\"what\":\"x\"}"));
        history.add(answers("c1", "look", "first answer ".repeat(100)));
        history.add(calls("c2", "look", "{\"what\":\"x\"}"));
        history.add(answers("c2", "look", "second answer ".repeat(100)));
        history.add(calls("c3", "build", "{\"command\":\"mvn -q test\"}"));
        history.add(answers("c3", "build", "exit=1\n" + "a line\n".repeat(200)));
        history.add(calls("c4", "build", "{\"command\":\"mvn -q test -pl other\"}"));
        history.add(answers("c4", "build", "exit=0\n" + "a line\n".repeat(200)));

        Superseded.Result result = Superseded.shorten(history, tools(), List.of());

        assertThat(result.changed()).isFalse();
        assertThat(result.messages()).isSameAs(history);
    }
}
