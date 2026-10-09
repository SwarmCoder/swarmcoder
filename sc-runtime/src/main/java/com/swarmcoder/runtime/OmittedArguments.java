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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

/**
 * A tool call that leaves out an argument its tool says may be empty is run with the empty
 * string, not answered with an exception.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Live run 103 (section 76). The architect's {@code keep_for_workers} describes its
 * {@code lines} argument as "empty for all of a short result". The model took that at its word
 * five times in one turn and sent no {@code lines} at all. The tool was never reached: the
 * binding refused each call with "No argument provided for a required parameter", a stack trace
 * on the log, and five findings for the workers were not kept. A worker's {@code acceptance_test}
 * - "asked with nothing, every claimed method" - failed the same way in the same run.
 *
 * <p>Only a parameter that carries {@link AgentRuntime.MayBeOmitted} is filled, and only a
 * {@code String}. A tool that writes is not given an empty text it was never sent: nothing here
 * decides from a tool's name or from what an argument is called.
 */
final class OmittedArguments {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OmittedArguments() {
    }

    /** {@code calls}, each with the arguments its tool lets a caller leave out set to "". */
    static List<MessagePart.Tool.Call> filled(List<MessagePart.Tool.Call> calls,
                                              List<AgentRuntime.ToolBinding> tools) {
        if (calls == null || calls.isEmpty() || tools == null) {
            return calls;
        }
        List<MessagePart.Tool.Call> out = new ArrayList<>(calls.size());
        for (MessagePart.Tool.Call call : calls) {
            String args = call == null ? null : filledArguments(call.getTool(), call.getArgs(),
                tools);
            out.add(args == null ? call
                : call.copy(call.getId(), call.getTool(), args, call.getCacheControl()));
        }
        return out;
    }

    /** The arguments with what may be omitted filled in; null when nothing was missing. */
    static String filledArguments(String tool, String argsJson,
                                  List<AgentRuntime.ToolBinding> tools) {
        AgentRuntime.ToolBinding binding = null;
        for (AgentRuntime.ToolBinding candidate : tools) {
            if (candidate != null && candidate.name().equals(tool)) {
                binding = candidate;
                break;
            }
        }
        if (binding == null || binding.method() == null) {
            return null;
        }
        ObjectNode object;
        try {
            JsonNode read = argsJson == null || argsJson.isBlank() ? JSON.createObjectNode()
                : JSON.readTree(argsJson);
            if (read == null || !read.isObject()) {
                return null;
            }
            object = (ObjectNode) read;
        } catch (Exception unreadable) {
            return null; // the binding says what is wrong with arguments it cannot read
        }
        boolean changed = false;
        for (Parameter parameter : binding.method().getParameters()) {
            if (parameter.getType() == String.class
                    && parameter.isAnnotationPresent(AgentRuntime.MayBeOmitted.class)
                    && (!object.has(parameter.getName())
                        || object.get(parameter.getName()).isNull())) {
                object.put(parameter.getName(), "");
                changed = true;
            }
        }
        return changed ? object.toString() : null;
    }
}
