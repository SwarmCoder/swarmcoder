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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEVELOPER_CORRECTIONS section 113, finding 3: a worker's {@code body_of} failed three times in
 * the binding with "No argument provided for a required parameter: parameter #1 member of fun
 * ...oneBodyOf", a name its description never used. A tool of one argument now takes that
 * argument under whatever name it is sent; a tool of several is left as it was.
 */
class AToolOfOneArgumentTakesItUnderAnyNameTest {

    public String bodyOf(String member) {
        return member;
    }

    public String replace(String member, String newText) {
        return member + newText;
    }

    @Test
    void theOneArgumentOfAOneArgumentToolIsThatArgumentWhateverItIsCalled() throws Exception {
        List<AgentRuntime.ToolBinding> tools = List.of(
            new AgentRuntime.ToolBinding("body_of", "", this,
                getClass().getMethod("bodyOf", String.class)),
            new AgentRuntime.ToolBinding("replace_member", "", this,
                getClass().getMethod("replace", String.class, String.class)));

        assertThat(OmittedArguments.filledArguments("body_of", "{\"type\":\"Shop#open\"}", tools))
            .isEqualTo("{\"member\":\"Shop#open\"}");
        assertThat(OmittedArguments.filledArguments("body_of", "{\"member\":\"Shop#open\"}",
            tools)).as("a call that names it right is not touched").isNull();
        assertThat(OmittedArguments.filledArguments("body_of",
            "{\"type\":\"Shop\",\"what\":\"open\"}", tools))
            .as("two arguments for one: nothing is guessed").isNull();
        assertThat(OmittedArguments.filledArguments("replace_member", "{\"type\":\"Shop#open\"}",
            tools)).as("a tool of two arguments is left as it was").isNull();
    }
}
