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

import ai.koog.prompt.message.MessagePart;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The samples here are verbatim model output from the run of 2026-08-29 in which 27 workers
 * finished having written nothing, plus the write that the same failure mode would have swallowed.
 */
class TextEmittedToolCallsTest {

    private static final List<String> TOOLS =
        List.of("exec", "read", "apply_diff", "write_file", "lookup_api", "report_done");

    @Test
    void recoversTheTextualHistoryShape() {
        List<MessagePart.Tool.Call> calls = TextEmittedToolCalls.parse(
            "\n\n{\"tool_call_id\":\"call_2f3b2b2b2b2b2b2b2b2b2b2b\",\"tool_name\":\"read\","
                + "\"tool_args\":{\"path\":\"pom.xml\"}}", TOOLS);

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getTool()).isEqualTo("read");
        assertThat(calls.get(0).getId()).isEqualTo("call_2f3b2b2b2b2b2b2b2b2b2b2b");
        assertThat(calls.get(0).getArgsJson().get("path").toString()).contains("pom.xml");
    }

    @Test
    void recoversTwoCallsWrittenBackToBack() {
        List<MessagePart.Tool.Call> calls = TextEmittedToolCalls.parse(
            "{\"tool_call_id\":\"a\",\"tool_name\":\"read\",\"tool_args\":{\"path\":\"A.java\"}}"
                + "{\"tool_call_id\":\"b\",\"tool_name\":\"read\",\"tool_args\":{\"path\":\"B.java\"}}",
            TOOLS);

        assertThat(calls).extracting(MessagePart.Tool.Call::getTool).containsExactly("read", "read");
    }

    @Test
    void recoversAWriteWhoseContentIsFullOfBraces() {
        String content = "package a;\\n\\npublic class A {\\n  void f() { g(\\\"{}\\\"); }\\n}\\n";
        List<MessagePart.Tool.Call> calls = TextEmittedToolCalls.parse(
            "Here is the file:\n```json\n{\"tool_name\":\"write_file\",\"tool_args\":"
                + "{\"path\":\"src/main/java/A.java\",\"content\":\"" + content + "\"}}\n```",
            TOOLS);

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getTool()).isEqualTo("write_file");
        assertThat(calls.get(0).getArgsJson().get("content").toString()).contains("public class A");
    }

    @Test
    void acceptsTheOpenAiSpellingAndAStringifiedArgumentObject() {
        List<MessagePart.Tool.Call> calls = TextEmittedToolCalls.parse(
            "{\"name\":\"exec\",\"arguments\":\"{\\\"command\\\":\\\"mvn -q test\\\"}\"}", TOOLS);

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getTool()).isEqualTo("exec");
        assertThat(calls.get(0).getArgsJson().get("command").toString()).contains("mvn -q test");
    }

    @Test
    void ignoresProseAndUnknownTools() {
        assertThat(TextEmittedToolCalls.parse("I have finished the change and it compiles.", TOOLS))
            .isEmpty();
        assertThat(TextEmittedToolCalls.parse(
            "{\"tool_name\":\"delete_everything\",\"tool_args\":{}}", TOOLS)).isEmpty();
        assertThat(TextEmittedToolCalls.parse(
            "{\"tool_name\":\"read\",\"tool_args\":\"not an object\"}", TOOLS)).isEmpty();
        assertThat(TextEmittedToolCalls.parse("{ not json at all }", TOOLS)).isEmpty();
        assertThat(TextEmittedToolCalls.parse(null, TOOLS)).isEmpty();
    }
}
