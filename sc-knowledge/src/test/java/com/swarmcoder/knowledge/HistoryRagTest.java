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
package com.swarmcoder.knowledge;

import com.swarmcoder.domain.AgentSessionRecord;
import com.swarmcoder.domain.TraceEvent;
import com.swarmcoder.domain.TraceEventKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryRagTest {

    @TempDir
    Path indexDir;

    private static AgentSessionRecord session(String outcome, String toolResult) {
        UUID id = UUID.randomUUID();
        return new AgentSessionRecord(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
            "worker-0", "qwen", 0.2, Instant.now(), Instant.now(), outcome, null, 2, 100,
            List.of(new TraceEvent(0, Instant.now(), TraceEventKind.TOOL_RESULT, "exec", toolResult, null, 50)));
    }

    @Test
    void indexesAndSearchesTranscripts() throws Exception {
        HistoryRag rag = new HistoryRag(indexDir);
        AgentSessionRecord npe = session("KILLED", "NullPointerException in RoutingModule.dispatch");
        AgentSessionRecord ok = session("COMPLETED", "BUILD SUCCESSFUL all tests green");
        rag.index(npe);
        rag.index(ok);

        List<HistoryRag.Hit> hits = rag.search("NullPointerException routing", 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).sessionId()).isEqualTo(npe.id().toString());
        assertThat(hits.get(0).snippet()).contains("NullPointerException");
    }

    @Test
    void reindexReplacesNotDuplicates() throws Exception {
        HistoryRag rag = new HistoryRag(indexDir);
        AgentSessionRecord s = session("COMPLETED", "compileJava OK");
        rag.index(s);
        rag.index(s); // same id again

        assertThat(rag.search("compileJava", 10)).hasSize(1);
    }

    @Test
    void emptyIndexAndBlankQueryAreSafe() throws Exception {
        HistoryRag rag = new HistoryRag(indexDir);
        assertThat(rag.search("anything", 5)).isEmpty();
        rag.index(session("COMPLETED", "x"));
        assertThat(rag.search("", 5)).isEmpty();
    }
}
