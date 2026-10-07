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
package com.swarmcoder.swarm;

import com.swarmcoder.testsupport.ModelCodeOnThisPc;
import com.swarmcoder.domain.*;
import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.runtime.AgentRuntime;
import com.swarmcoder.runtime.KoogAgentRuntime;
import com.swarmcoder.runtime.PromptBundle;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replays ONE real worker session against a live model server through a recording proxy, so the
 * exact bytes SwarmCoder sends (system prompt, tool schemas, conversation history) and the exact
 * bytes the model answers with are on disk afterwards.
 *
 * <pre>
 * mvn -o test -pl sc-swarm -am -Dtest=WorkerWriteReplayTest -Dsurefire.failIfNoSpecifiedTests=false \
 *   -Dswarmcoder.live.baseUrl=http://192.168.0.10:8002/v1 \
 *   -Dswarmcoder.live.model=qwen3.8-27b \
 *   -Dswarmcoder.live.repo=C:/work/swarmcoder/dev/bookshelf-demo \
 *   -Dswarmcoder.replay.textualToolHistory=true \
 *   -Dswarmcoder.replay.dump=C:/tmp/replay
 * </pre>
 */
@ModelCodeOnThisPc
class WorkerWriteReplayTest {

    @TempDir
    Path workspaceRoot;

    @Test
    @RunsWhen(Need.LIVE_MODEL)
    void oneWorkerSession() throws Exception {
        String baseUrl = System.getProperty("swarmcoder.live.baseUrl");
        String model = System.getProperty("swarmcoder.live.model", "qwen3.8-27b");
        Path source = Path.of(System.getProperty("swarmcoder.live.repo"));
        boolean textualToolHistory =
            Boolean.parseBoolean(System.getProperty("swarmcoder.replay.textualToolHistory", "true"));
        Path dump = Path.of(System.getProperty("swarmcoder.replay.dump",
            workspaceRoot.resolve("dump").toString()));
        Files.createDirectories(dump);

        Path workspace = workspaceRoot.resolve("repo");
        copyTree(source, workspace);
        run(workspace, "git init -q .");
        run(workspace, "git add -A");
        run(workspace, "git -c user.email=t@t -c user.name=t commit -q -m base");

        RecordingProxy proxy = new RecordingProxy(baseUrl, dump);
        proxy.start();
        try {
            Task task = task();
            PromptBundle bundle = bundle(task);
            WorkerToolbox toolbox = new WorkerToolbox(workspace, task,
                com.swarmcoder.runtime.ApiLookup.UNAVAILABLE, workspace);
            toolbox.setReferenceHint("");

            ModelQuirks quirks = new ModelQuirks("replay", 32768, false, "enable_thinking",
                "/no_think", textualToolHistory, true, false, 65536, 32768, 1024, 16, false);

            AgentRuntime.SessionSpec spec = new AgentRuntime.SessionSpec(
                "worker-0",
                bundle.forWorker(WorkerPersonas.text("minimal-diff"), null),
                new AgentRuntime.ModelEndpoint(proxy.localBaseUrl(), "", model, 65536, quirks),
                0.2,
                30,
                toolbox.bindings(),
                new AgentRuntime.TurnGuard() {
                    @Override
                    public Optional<KillReason> check(TurnInfo info) {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<String> steeringGiven() {
                        return Optional.ofNullable(toolbox.drainSteer());
                    }
                });

            AgentRuntime.SessionResult result;
            try (AgentRuntime.AgentSession session = new KoogAgentRuntime().open(spec)) {
                result = session.run("Begin now. Implement the task from your instructions; "
                    + "call report_done when the change is complete and verified.");
            }

            String status = run(workspace, "git status --porcelain");
            String verdict = "textualToolHistory=" + textualToolHistory
                + "\nturns=" + result.turns()
                + "\ntokens=" + result.tokensUsed()
                + "\nkill=" + result.killReason()
                + "\nhasWritten=" + toolbox.hasWritten()
                + "\nrequests=" + proxy.count()
                + "\ngit status --porcelain:\n" + status
                + "\nfinalOutput:\n" + result.finalOutput() + "\n";
            Files.writeString(dump.resolve("VERDICT-" + textualToolHistory + ".txt"), verdict);
            System.out.println("=== REPLAY VERDICT ===\n" + verdict);
        } finally {
            proxy.stop();
        }
    }

    private static Task task() {
        return new Task(UUID.randomUUID(), 1,
            "Book input validator",
            """
            Add a validator for book input to the shared module.

            Create bookshelf-demo-shared/src/main/java/com/swarmcoder/demo/bookshelf/model/BookValidator.java
            with a public class BookValidator exposing a static method
            `validate(String title, String author)` that returns a java.util.List<String> of
            human-readable problems: "Title is required" when the title is null or blank,
            "Author is required" when the author is null or blank. An empty list means valid.
            """,
            Set.of("bookshelf-demo-shared/src/main"), Set.of("bookshelf-demo-shared"),
            List.of(), "bookshelf-demo-shared/src/test/java/swarm", null,
            new TokenBudget(32000, 4000, 500000, 30),
            new SwarmPolicy(1, false, 0.2, 0.2, List.of("minimal-diff")), TaskState.READY);
    }

    /** Mirrors SwarmDispatcher.buildBundle for a task with no brief and no guidelines. */
    private static PromptBundle bundle(Task task) {
        String instructions = "Task: " + task.title() + "\n" + task.instructions() + "\n"
            + "You may ONLY modify these paths: " + task.writeSet().stream().sorted().toList() + "\n"
            + "Acceptance tests in " + task.acceptanceTestDir() + " are protected; never modify them. "
            + "Read the test your task must satisfy with acceptance_test (its claimed test methods "
            + "and the helpers they use) before you write code; verification places the committed "
            + "test, so nothing you write there counts.\n";
        return PromptBundle.builder()
            .systemRole("You are a software engineering worker agent. Implement exactly the task "
                + "described below in the repository you have tools for.")
            .workflowRules("Work in small steps: inspect with read/exec; modify with write_file "
                + "(full file content — reliable) or apply_diff (unified diff); verify by running "
                + "builds/tests with exec; then call report_done with a short summary when the "
                + "change is complete and verified. If apply_diff is rejected, read the file and "
                + "use write_file instead of retrying the diff. ALWAYS use repository-RELATIVE "
                + "paths (e.g. `pom.xml`, `src/main/java/App.java`) — never absolute paths; your "
                + "tools already operate at the repository root.\n"
                + "When you do not know an API: call lookup_api FIRST. It searches this "
                + "project's reference documentation and any configured documentation server, "
                + "and your knowledge brief lists the documents by name. Do NOT unpack or "
                + "decompile jars (`jar xf`, `javap`, a decompiler) to work an API out — that "
                + "costs many turns and tells you less than one lookup_api call. Only fall back "
                + "to inspecting compiled classes when lookup_api has told you there is no "
                + "documentation, and give it a few turns at most before writing your best "
                + "attempt and letting the build correct you.")
            .taskInstructions(instructions)
            .build();
    }

    // ---- plumbing ----

    /** Forwards /v1/chat/completions upstream, writing every request and response to disk. */
    private static final class RecordingProxy {
        private final String upstream;
        private final Path dump;
        private final AtomicInteger seq = new AtomicInteger();
        private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30)).build();
        private HttpServer server;

        RecordingProxy(String upstreamBaseUrl, Path dump) {
            this.upstream = upstreamBaseUrl.endsWith("/")
                ? upstreamBaseUrl.substring(0, upstreamBaseUrl.length() - 1) : upstreamBaseUrl;
            this.dump = dump;
        }

        void start() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
            server.start();
        }

        String localBaseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        int count() {
            return seq.get();
        }

        void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        private void handle(HttpExchange exchange) throws java.io.IOException {
            byte[] body = exchange.getRequestBody().readAllBytes();
            int n = seq.incrementAndGet();
            String path = exchange.getRequestURI().toString();
            // The configured base already ends in /v1 and the client asks for /v1/... too.
            String target = upstream.substring(0, upstream.length() - 3) + path;
            Files.write(dump.resolve(String.format("%03d-request.json", n)), body);
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofMinutes(15))
                    .method(exchange.getRequestMethod(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
                exchange.getRequestHeaders().forEach((k, v) -> {
                    String key = k.toLowerCase(java.util.Locale.ROOT);
                    if (!key.equals("host") && !key.equals("connection")
                        && !key.equals("content-length") && !key.equals("upgrade")
                        && !key.equals("http2-settings") && !key.equals("transfer-encoding")) {
                        v.forEach(value -> b.header(k, value));
                    }
                });
                HttpResponse<byte[]> response = http.send(b.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
                Files.write(dump.resolve(String.format("%03d-response.json", n)), response.body());
                exchange.getResponseHeaders().add("Content-Type",
                    response.headers().firstValue("content-type").orElse("application/json"));
                exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response.body());
                }
            } catch (Exception e) {
                byte[] err = ("{\"error\":\"proxy: " + e + "\"}").getBytes(StandardCharsets.UTF_8);
                Files.write(dump.resolve(String.format("%03d-proxy-error.txt", n)), err);
                exchange.sendResponseHeaders(502, err.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(err);
                }
            }
        }
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (var stream = Files.walk(from)) {
            for (Path p : stream.toList()) {
                String rel = from.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith(".git/") || rel.startsWith("target/") || rel.contains("/target/")) {
                    continue;
                }
                Path dest = to.resolve(rel);
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest);
                }
            }
        }
    }

    private static String run(Path dir, String command) throws Exception {
        return new com.swarmcoder.verify.LocalProcessExecTarget(dir).exec(command, 120).output();
    }
}
