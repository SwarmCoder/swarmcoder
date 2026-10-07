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
package com.swarmcoder.workflow;

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.domain.DesignDocument;
import com.swarmcoder.domain.SwarmPolicy;
import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.TaskGraph;
import com.swarmcoder.domain.TaskState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Brownfield harness run 43, 2026-09-26, target {@code jsoup}, an existing plain-Java library. The
 * story was a bug fix to {@code org.jsoup.parser.Parser}, and the design named it — the acceptance
 * test reads the real {@code NamespaceXml} constant off it — because that is genuinely the type the
 * fix and its test are written against. PLAN attempt 1 was rejected here anyway: "no task delivers
 * the contract {@code org.jsoup.parser.Parser{String NamespaceXml; }}", the VOCABULARY rule built
 * for run 13, where every named type really was new. {@code Parser} and {@code NamespaceXml} were
 * never missing — they were already in the checkout the plan was layered onto, before the architect
 * ever ran.
 *
 * <p>{@link TaskGraphValidator#validate(TaskGraph, StoryScope, com.swarmcoder.verify.BuildLayout.Layout,
 * DesignDocument, Path)} now reads that checkout with {@link com.swarmcoder.knowledge.ContractDelivery}
 * before objecting: an existing type satisfying every member the contract names needs no delivering
 * task at all; an existing type missing a member the contract names still needs one, worded around
 * the member rather than the type; a type genuinely absent from the checkout is unchanged.
 */
class ExistingTypesCountAsDeliveredTest {

    @TempDir
    Path repo;

    private final TaskGraphValidator validator = new TaskGraphValidator();

    private static Task unrelatedTask() {
        return new Task(UUID.randomUUID(), 1, "Fix the namespace bug",
            "fix it", Set.of("src/main/java/org/jsoup/parser/Tokeniser.java"), Set.of(),
            List.of(), null, null, null,
            new SwarmPolicy(1, false, 0.2, 0.2, List.of()), TaskState.PENDING);
    }

    private static TaskGraph graph(Task task) {
        return new TaskGraph(UUID.randomUUID(), 1, null, List.of(task), List.of());
    }

    private static DesignDocument designFor(ApiContract contract) {
        return new DesignDocument(UUID.randomUUID(), 1, "fix a jsoup parsing bug", List.of(),
            new ArrayList<>(), List.of(contract), List.of(), null, Instant.now());
    }

    private void writeExistingParser(String namespaceXmlLine) throws Exception {
        Path file = repo.resolve("src/main/java/org/jsoup/parser/Parser.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
            package org.jsoup.parser;

            public class Parser {
                public static final String NamespaceHtml = "http://www.w3.org/1999/xhtml";
                %s

                public Parser() {
                }
            }
            """.formatted(namespaceXmlLine));
    }

    @Test
    void anExistingTypeWithEveryPromisedMemberNeedsNoDeliveringTask() throws Exception {
        writeExistingParser("public static final String NamespaceXml = \"http://www.w3.org/XML/1998/namespace\";");
        ApiContract parser = new ApiContract(UUID.randomUUID(), "Parser", "the jsoup parser", null,
            "org.jsoup.parser.Parser", List.of("String NamespaceXml"));
        DesignDocument design = designFor(parser);

        TaskGraphValidator.Verdict verdict = validator.validate(
            graph(unrelatedTask()), null, null, design, repo);

        assertThat(verdict.ok())
            .as("org.jsoup.parser.Parser and its NamespaceXml constant already exist in the "
                + "checkout; a bug-fix plan is not asked to 'deliver' code that is already there")
            .isTrue();
        assertThat(verdict.violations()).isEmpty();
    }

    @Test
    void anExistingTypeMissingAPromisedMemberStillNeedsADeliveringTask() throws Exception {
        // The constant the contract names is not on the existing type yet — this is the part of
        // the fix a task must still be told to write.
        writeExistingParser("// NamespaceXml does not exist yet");
        ApiContract parser = new ApiContract(UUID.randomUUID(), "Parser", "the jsoup parser", null,
            "org.jsoup.parser.Parser", List.of("String NamespaceXml"));
        DesignDocument design = designFor(parser);

        TaskGraphValidator.Verdict verdict = validator.validate(
            graph(unrelatedTask()), null, null, design, repo);

        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.violations()).anyMatch(v -> v.contains("no task delivers")
            && v.contains("NamespaceXml") && v.contains("org.jsoup.parser.Parser")
            && v.contains("already exists in this checkout"));
    }

    @Test
    void aTypeGenuinelyAbsentFromTheCheckoutIsUnchanged() {
        // The run-13 shape, checked against a real (but empty) checkout: nothing on disk declares
        // this type, so behaviour must be exactly what it always was.
        ApiContract rating = new ApiContract(UUID.randomUUID(), "Rating", "a book's rating", null,
            "com.swarmcoder.demo.bookshelf.Rating", List.of("int stars"));
        DesignDocument design = designFor(rating);

        TaskGraphValidator.Verdict verdict = validator.validate(
            graph(unrelatedTask()), null, null, design, repo);

        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.violations()).anyMatch(v -> v.contains("no task delivers the contract")
            && v.contains("com.swarmcoder.demo.bookshelf.Rating"));
    }

    @Test
    void aNullRepoRootBehavesExactlyAsGreenfieldAlwaysHas() {
        // No checkout to consult at all (a true greenfield run) — every named contract still needs
        // a delivering task, with no exception, exactly as before this parameter existed.
        ApiContract book = new ApiContract(UUID.randomUUID(), "Book", "a book", null,
            "com.swarmcoder.demo.bookshelf.Book", List.of("String title()"));
        DesignDocument design = designFor(book);

        TaskGraphValidator.Verdict verdict = validator.validate(
            graph(unrelatedTask()), null, null, design, null);

        assertThat(verdict.ok()).isFalse();
        assertThat(verdict.violations()).anyMatch(v -> v.contains("no task delivers the contract")
            && v.contains("com.swarmcoder.demo.bookshelf.Book"));
    }
}
