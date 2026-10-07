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
package com.swarmcoder.console;

import com.swarmcoder.console.api.RoleEntryDto;
import com.zeroz4j.api.GrowableBuffer;
import com.zeroz4j.api.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A settings row through its own hand-written serializer.
 *
 * <p>The row grew from five fields to eighteen when every model-specific behaviour became editable,
 * and the write and read halves are hand-written and positional. Transpose two and the settings
 * screen silently shows one model's output cap under another model's context size, with nothing at
 * build time saying a word. Every value below is distinct so a transposition cannot hide behind two
 * equal fields.
 */
class RoleEntryWireTest {

    @Test
    void everyModelSettingSurvivesTheWire() {
        RoleEntryDto row = new RoleEntryDto();
        row.setRoleId("worker0");
        row.setBaseUrl("http://box:8000");
        row.setApiKey("secret-key");
        row.setModelName("qwen38-flash-next");
        row.setThinking(1);
        row.setShape("qwen38-flash-next-125b");
        row.setMaxOutputTokens(8192);
        row.setThinkingKwarg("enable_thinking");
        row.setNoThinkDirective("/no_think");
        row.setTextualToolHistory(0);
        row.setJsonResponseFormat(1);
        row.setHttpVersion("1.1");
        row.setServedContextTokens(131072);
        row.setWorkingContextTokens(65536);
        row.setKvBytesPerToken(2048);
        row.setMaxConcurrentSequences(6);
        row.setVerified(1);
        row.setAvailableShapes("a,b,c");

        RoleEntryDto back = packedRoundTrip(row);

        assertThat(back.getRoleId()).isEqualTo("worker0");
        assertThat(back.getBaseUrl()).isEqualTo("http://box:8000");
        assertThat(back.getApiKey()).isEqualTo("secret-key");
        assertThat(back.getModelName()).isEqualTo("qwen38-flash-next");
        assertThat(back.getThinking()).isEqualTo(1);
        assertThat(back.getShape()).isEqualTo("qwen38-flash-next-125b");
        assertThat(back.getMaxOutputTokens()).isEqualTo(8192);
        assertThat(back.getThinkingKwarg()).isEqualTo("enable_thinking");
        assertThat(back.getNoThinkDirective()).isEqualTo("/no_think");
        assertThat(back.getTextualToolHistory()).isEqualTo(0);
        assertThat(back.getJsonResponseFormat()).isEqualTo(1);
        assertThat(back.getHttpVersion()).isEqualTo("1.1");
        assertThat(back.getServedContextTokens()).isEqualTo(131072);
        assertThat(back.getWorkingContextTokens()).isEqualTo(65536);
        assertThat(back.getKvBytesPerToken()).isEqualTo(2048);
        assertThat(back.getMaxConcurrentSequences()).isEqualTo(6);
        assertThat(back.getVerified()).isEqualTo(1);
        assertThat(back.getAvailableShapes()).isEqualTo("a,b,c");
    }

    @Test
    void anUntouchedRowSurvivesToo() {
        // A role nobody has configured: everything inherits, and the -1 / 0 / null defaults must
        // come back as themselves rather than as zeroes the resolver would treat as a decision.
        RoleEntryDto row = new RoleEntryDto();
        row.setRoleId("judge");

        RoleEntryDto back = packedRoundTrip(row);

        assertThat(back.getRoleId()).isEqualTo("judge");
        assertThat(back.getThinking()).isEqualTo(-1);
        assertThat(back.getTextualToolHistory()).isEqualTo(-1);
        assertThat(back.getJsonResponseFormat()).isEqualTo(-1);
        assertThat(back.getVerified()).isEqualTo(-1);
        assertThat(back.getMaxOutputTokens()).isZero();
        assertThat(back.getKvBytesPerToken()).isZero();
    }

    private static RoleEntryDto packedRoundTrip(RoleEntryDto row) {
        GrowableBuffer out = new GrowableBuffer();
        row.writeToBuffer(out, new ObjectMapper());
        ByteBuffer in = ByteBuffer.wrap(out.toByteArray());
        RoleEntryDto back = new RoleEntryDto();
        back.readFromBuffer(in, new ObjectMapper());
        return back;
    }
}
