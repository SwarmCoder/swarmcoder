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
package com.swarmcoder.app;

import com.swarmcoder.testsupport.LocalCheckouts;
import com.swarmcoder.knowledge.Context7Client;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup line that would have saved a whole run (§32).
 *
 * <p>Failing quietly per lookup is right: one dead documentation call must never kill a run.
 * Failing quietly at STARTUP is not. No documentation server had ever been configured, the
 * built-in address pointed at a port nothing was listening on, and the client is deliberately
 * built to fail quietly — so every question a worker asked came back "no documentation found",
 * ten workers spent about 180,000 tokens taking a library apart, and nothing anywhere said the
 * documentation was missing.
 *
 * <p>These are the exact words an operator is given. Text a person depends on is the product, so
 * the words are asserted, not the shape.
 */
class KnowledgeSourceReportTest {

    private static final List<Path> FOLDERS = List.of(LocalCheckouts.find("demostack"));
    private static final String ADDRESS = "https://mcp.context7.com/mcp";

    @Test
    void aWorkingSetupSaysWhatTheAgentsCanReadAndNothingElse() {
        List<String> lines = EnvironmentChecks.reportKnowledgeSources(
            "demo", FOLDERS, 43, Context7Client.Posture.READY, ADDRESS);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
            .contains("43 documents")
            .contains("searched first");
    }

    /** Three broken states, three different fixes — an operator must be able to tell them apart. */
    @Test
    void eachWayOfBeingBrokenSaysWhatToDoAboutIt() {
        String noKey = only(EnvironmentChecks.reportKnowledgeSources(
            "demo", FOLDERS, 43, Context7Client.Posture.NO_KEY, ADDRESS));
        assertThat(noKey)
            .contains("CONTEXT7_API_KEY")
            .contains("restart")
            .as("a key set after the window opened is not seen until restart")
            .contains("already open");

        String rejected = only(EnvironmentChecks.reportKnowledgeSources(
            "demo", FOLDERS, 43, Context7Client.Posture.KEY_REJECTED, ADDRESS));
        assertThat(rejected).contains("REFUSED").contains("set but not accepted");

        String unreachable = only(EnvironmentChecks.reportKnowledgeSources(
            "demo", FOLDERS, 43, Context7Client.Posture.UNREACHABLE, ADDRESS));
        assertThat(unreachable).contains("nothing answered").contains("mcpServers");

        // None of them may ever carry a credential.
        for (String line : List.of(noKey, rejected, unreachable)) {
            assertThat(line).doesNotContain("ctx7sk").doesNotContain("Bearer");
        }
    }

    /** With good local documentation the server being down is a footnote, not a crisis. */
    @Test
    void localDocumentationSoftensTheServerBeingDown() {
        String withDocs = only(EnvironmentChecks.reportKnowledgeSources(
            "demo", FOLDERS, 43, Context7Client.Posture.NO_KEY, ADDRESS), 1);
        assertThat(withDocs).contains("only affects questions about other people's libraries");

        String withoutDocs = only(EnvironmentChecks.reportKnowledgeSources(
            "demo", List.of(), 0, Context7Client.Posture.NO_KEY, ADDRESS));
        assertThat(withoutDocs)
            .contains("no documentation at all")
            .contains("taking compiled libraries apart");
    }

    /** Reference folders that hold nothing readable are said out loud, not shrugged off. */
    @Test
    void emptyReferenceFoldersAreCalledOut() {
        List<String> lines = EnvironmentChecks.reportKnowledgeSources(
            "demo", FOLDERS, 0, Context7Client.Posture.READY, ADDRESS);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
            .contains("NO documentation")
            .contains("'docs' folder");
    }

    private static String only(List<String> lines) {
        return only(lines, lines.size() - 1);
    }

    private static String only(List<String> lines, int index) {
        return lines.get(index);
    }
}
