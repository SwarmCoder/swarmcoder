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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swarmcoder.domain.ApiContract;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The command line behind the unseen-code experiment: build one task's brief and print it.
 *
 * <p>Run from the repository root, offline:
 *
 * <pre>
 * mvn -o -q -pl sc-knowledge -am -DskipTests test-compile
 * mvn -o -q -pl sc-knowledge exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=com.swarmcoder.knowledge.BriefLab \
 *     -Dexec.args="--task dev/experiment/unseen-code/bookshelf-task.json --out brief.md \
 *                  --skeleton-dir some/tree --ingredients examples,deps,recipe,cards"
 * </pre>
 *
 * <p>Nothing here is production code. It exists so the CONTENT of a brief can be changed and
 * measured against a live model in minutes rather than through a whole SwarmCoder run.
 */
public final class BriefLab {

    private BriefLab() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].startsWith("--")) {
                options.put(args[i].substring(2), args[i + 1]);
            }
        }
        Path taskFile = Path.of(options.getOrDefault("task", ""));
        JsonNode task = new ObjectMapper().readTree(Files.readString(taskFile,
            StandardCharsets.UTF_8));

        Path target = Path.of(task.path("targetRoot").asText());
        List<KnowledgeCurator.Root> roots = new ArrayList<>();
        roots.add(new KnowledgeCurator.Root("project", target, "local"));
        for (JsonNode reference : task.path("referenceRoots")) {
            Path path = Path.of(reference.path("path").asText());
            roots.add(new KnowledgeCurator.Root(reference.path("label").asText(), path, "local"));
        }

        List<ApiContract> contracts = new ArrayList<>();
        for (JsonNode node : task.path("contracts")) {
            List<String> members = new ArrayList<>();
            for (JsonNode member : node.path("members")) {
                members.add(member.asText());
            }
            contracts.add(new ApiContract(UUID.randomUUID(), node.path("typeName").asText(),
                node.path("purpose").asText(), "", node.path("typeName").asText(), members));
        }

        Set<String> words = TaskBrief.taskWords(task.path("title").asText(),
            task.path("instructions").asText(), task.path("constraints").asText());

        long started = System.currentTimeMillis();
        List<WorkedExamples.Shape> shapes = WorkedExamples.shapes(roots);
        WorkedExamples.Selection selection = WorkedExamples.select(shapes, contracts, words);
        long took = System.currentTimeMillis() - started;

        System.err.println("scanned " + shapes.size() + " java files in " + took + " ms");
        System.err.println("chose project: " + selection.projectLabel() + "  ("
            + selection.project() + ")");
        for (WorkedExamples.Match match : selection.matches()) {
            System.err.printf("  %-58s <- %-42s %.3f%n", match.contract().typeName(),
                match.example().simpleName(), match.score());
        }
        for (WorkedExamples.Shape neighbour : selection.neighbours()) {
            System.err.println("  (also) " + neighbour.relative());
        }
        if (options.containsKey("shortlists")) {
            for (ApiContract contract : contracts) {
                System.err.println("shortlist for " + contract.typeName() + ":");
                for (WorkedExamples.Match match
                        : WorkedExamples.shortlistFor(shapes, contract, words, 6)) {
                    System.err.printf("   %.3f  %s%n", match.score(), match.example().relative());
                }
            }
        }

        String flags = options.getOrDefault("ingredients", "examples,deps");
        TaskBrief.Ingredients want = new TaskBrief.Ingredients(
            flags.contains("examples"), flags.contains("deps"), flags.contains("recipe"),
            flags.contains("cards"), flags.contains("skeleton"));
        int budget = Integer.parseInt(options.getOrDefault("budget", "200000"));
        String brief = TaskBrief.render(target, selection, want, budget);

        if (options.containsKey("skeleton-dir")) {
            Path tree = Path.of(options.get("skeleton-dir"));
            Skeleton.Result result = Skeleton.write(tree, contracts, selection);
            // FIRST, not last. The examples are reference; the skeleton is the instruction, and
            // a worker reads front to back. Measured in variant 1: given 48,000 characters of
            // working example and nothing to do first, the model spent 88 turns and 62 shell
            // commands confirming the API against the jars and never once ran the build.
            brief = result.note() + brief;
            System.err.println("skeleton files written: " + result.files().size());
            for (Path file : result.files()) {
                System.err.println("  " + tree.relativize(file).toString().replace('\\', '/'));
            }
            for (String change : result.buildChanges()) {
                System.err.println("  build: " + change);
            }
        }

        String out = options.getOrDefault("out", "");
        if (out.isEmpty()) {
            System.out.println(brief);
        } else {
            Files.writeString(Path.of(out), brief, StandardCharsets.UTF_8);
            System.err.println("wrote " + brief.length() + " characters to " + out);
        }
    }
}
