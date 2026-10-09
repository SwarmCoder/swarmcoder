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

import static org.assertj.core.api.Assertions.assertThat;

import ai.koog.prompt.message.MessagePart;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Live run 103 (section 76): five calls of one tool in one turn left out an argument the tool
 * describes as "empty for all of a short result", and each was answered by the binding with an
 * exception instead of by the tool. Nothing was kept for the workers from that turn.
 */
class AnArgumentAToolLetsACallerLeaveOutIsEmptyTest {

    /** A tool with one argument a caller may leave out and one it may not. */
    public static final class Tools {
        public String keep(String lookup, @AgentRuntime.MayBeOmitted String lines) {
            return lookup + "|" + lines;
        }

        public String write(String path, String content) {
            return path + "|" + content;
        }
    }

    private static List<AgentRuntime.ToolBinding> bindings() throws Exception {
        Tools tools = new Tools();
        return List.of(
            new AgentRuntime.ToolBinding("keep", "keeps", tools,
                Tools.class.getMethod("keep", String.class, String.class)),
            new AgentRuntime.ToolBinding("write", "writes", tools,
                Tools.class.getMethod("write", String.class, String.class)));
    }

    @Test
    void theOmittedArgumentIsFilledAndNothingElseIsTouched() throws Exception {
        List<MessagePart.Tool.Call> calls = List.of(
            new MessagePart.Tool.Call("a", "keep", "{\"lookup\":\"body_of Order#save\"}"),
            new MessagePart.Tool.Call("b", "keep", "{\"lookup\":\"x\",\"lines\":\"3-9\"}"),
            new MessagePart.Tool.Call("c", "keep", "{\"lookup\":\"x\",\"lines\":null}"));

        List<MessagePart.Tool.Call> filled = OmittedArguments.filled(calls, bindings());

        assertThat(filled).hasSize(3);
        assertThat(filled.get(0).getId()).isEqualTo("a");
        assertThat(filled.get(0).getArgsJson().get("lines").toString()).isEqualTo("\"\"");
        assertThat(filled.get(0).getArgsJson().get("lookup").toString()).contains("Order#save");
        assertThat(filled.get(1)).as("given: left as it was").isSameAs(calls.get(1));
        assertThat(filled.get(2).getArgsJson().get("lines").toString()).isEqualTo("\"\"");
    }

    @Test
    void anArgumentNotMarkedIsNeverInvented() throws Exception {
        // A tool that writes is not handed an empty text it was never sent.
        assertThat(OmittedArguments.filledArguments("write", "{\"path\":\"A.java\"}", bindings()))
            .isNull();
        assertThat(OmittedArguments.filledArguments("keep", "{\"lines\":\"1-2\"}", bindings()))
            .as("the unmarked argument stays missing, and the binding says so").isNull();
        assertThat(OmittedArguments.filledArguments("unknown", "{}", bindings())).isNull();
        assertThat(OmittedArguments.filledArguments("keep", "not json", bindings())).isNull();
    }
}
