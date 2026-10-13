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
package com.swarmcoder.verify;

import com.swarmcoder.domain.HostExecution;
import com.swarmcoder.sandbox.DockerSandboxManager;
import com.swarmcoder.testsupport.Need;
import com.swarmcoder.testsupport.RunsWhen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An application that does not start says why, and is not waited for once it has ended
 * (DEVELOPER_CORRECTIONS section 92). Live supervised build, 2026-10-13: the application of a
 * merged tree refused to start and exited within seconds, saying why in its own output. The
 * product waited the whole 300 seconds for a port nothing would open, kept none of the output,
 * and stopped the build with "nothing answered within 300 seconds".
 */
class AnApplicationThatDoesNotStartSaysWhyTest {

    private static final String UI_IMAGE = "swarmcoder-worker-ui:latest";

    private static VerifySpec contract(String serve, int readySeconds) {
        return new VerifySpec("maven", List.of(), List.of(), List.of(), List.of(), List.of(),
            null, 0, new VerifySpec.BrowserSpec(serve, null, readySeconds, List.of(), 0));
    }

    private static JourneyFile.Journey journey() {
        JourneyFile.Read read = JourneyFile.read(
            "app/src/test/java/swarm/accept/a-book-is-added.journey.yaml", """
            journey: a-book-is-added
            steps:
              - expectVisible: "#rows"
            """);
        assertThat(read.ok()).as(read.objection()).isTrue();
        return read.journey();
    }

    // ------------------------------------------------------------------ no container needed

    /** A container whose application is this fake's: ended or running, and what it printed. */
    private static class Place implements JourneyRunner.Start, ExecTarget {
        final ExecTarget.Exited exited;
        final String printed;
        int probes;

        Place(ExecTarget.Exited exited, String printed) {
            this.exited = exited;
            this.printed = printed;
        }

        @Override public ExecTarget target() {
            return this;
        }

        @Override public void close() { }

        @Override public ExecResult exec(String command, int timeoutSeconds) {
            String out = "";
            if (command.contains("SC_BROWSER_PRESENT")) {
                out = "SC_BROWSER_PRESENT";
            } else if (command.contains("SC_READY")) {
                probes++; // nothing ever answers
            }
            return new ExecResult(0, out, false, Duration.ofMillis(1));
        }

        @Override public String readFile(String relativePath, int maxBytes) {
            return null;
        }

        @Override public List<String> listFiles(String relativeDir, String suffix) {
            return List.of();
        }

        @Override public void deleteDir(String relativePath) { }

        @Override public ServiceHandle startService(String command) {
            return new ServiceHandle() {
                @Override public boolean isAlive() {
                    return exited == null;
                }
                @Override public Optional<Exited> exited() {
                    return Optional.ofNullable(exited);
                }
                @Override public String outputSoFar() {
                    return printed;
                }
                @Override public void close() { }
            };
        }

        @Override public Optional<String> hostCannotReachServices() {
            return Optional.of("inside a container");
        }

        @Override public boolean browserChecksRunInside() {
            return true;
        }
    }

    @Test
    void anApplicationThatHasEndedIsNotWaitedForAndItsExitAndOutputAreKept() {
        // More than is kept: a first line, a long middle, a last line.
        String middle = "filler line that says nothing\n".repeat(2_000);
        String printed = "FIRST: the container refused com.example.server.BookServiceImpl.db\n"
            + middle + "LAST: stopped\n";
        Place place = new Place(new ExecTarget.Exited(1, 4), printed);
        List<byte[]> stored = new ArrayList<>();
        StringBuilder log = new StringBuilder();

        long before = System.nanoTime();
        JourneyRunner.Outcome outcome = JourneyRunner.run(() -> place,
            contract("java -jar app.jar", 300), List.of(journey()), content -> {
                stored.add(content);
                return "ref-of-the-output";
            }, log);
        long seconds = (System.nanoTime() - before) / 1_000_000_000L;

        assertThat(seconds).as("the 300 seconds are not waited out").isLessThan(30);
        assertThat(place.probes).as("nothing is asked of an application that has ended").isZero();
        assertThat(outcome.made()).isFalse();
        assertThat(outcome.didNotStart())
            .contains("the application exited with code 1 after 4 seconds")
            .contains("http://127.0.0.1:18080/ inside the container")
            .doesNotContain("within 300 seconds");
        StartFailure failure = outcome.startFailure();
        assertThat(failure).isNotNull();
        assertThat(failure.exited()).isEqualTo(new ExecTarget.Exited(1, 4));
        assertThat(failure.shortFact()).isEqualTo("it exited with code 1 after 4 seconds");
        assertThat(failure.output().first()).startsWith("FIRST: the container refused");
        assertThat(failure.output().first().getBytes(StandardCharsets.UTF_8))
            .hasSize(ServeOutput.FIRST_BYTES);
        assertThat(failure.output().last()).endsWith("LAST: stopped\n");
        assertThat(failure.output().last().getBytes(StandardCharsets.UTF_8))
            .hasSize(ServeOutput.LAST_BYTES);
        assertThat(failure.output().leftOut())
            .isEqualTo(printed.length() - ServeOutput.FIRST_BYTES - ServeOutput.LAST_BYTES);
        assertThat(failure.output().text()).contains("bytes of output left out here");
        // Stored where blob_text reads it, and the place is named.
        assertThat(stored).singleElement().satisfies(bytes ->
            assertThat(new String(bytes, StandardCharsets.UTF_8))
                .isEqualTo(failure.output().text()));
        assertThat(failure.outputRef()).isEqualTo("ref-of-the-output");
        assertThat(failure.whereToRead()).contains("blob_text ref=ref-of-the-output");
        assertThat(log.toString()).contains("exited with code 1 after 4 seconds")
            .contains("FIRST: the container refused").contains("LAST: stopped");
        // The last lines that fit a place with little room: whole lines, the end of the output.
        assertThat(failure.output().lastLines(60)).isEqualTo(
            "filler line that says nothing\nLAST: stopped");
    }

    @Test
    void anApplicationStillRunningWhenTheWaitEndsIsSaidToBeStillRunning() {
        Place place = new Place(null, "listening soon\n");

        JourneyRunner.Outcome outcome = JourneyRunner.run(() -> place,
            contract("java -jar app.jar", 2), List.of(journey()), BlobSink.NONE,
            new StringBuilder());

        assertThat(place.probes).as("it was asked while it ran").isPositive();
        assertThat(outcome.didNotStart())
            .contains("nothing answered http://127.0.0.1:18080/ inside the container within 2 "
                + "seconds")
            .contains("still running when the wait ended");
        assertThat(outcome.startFailure().exited()).isNull();
        assertThat(outcome.startFailure().shortFact())
            .isEqualTo("it was still running and had not answered after 2 seconds");
        assertThat(outcome.startFailure().output().text()).isEqualTo("listening soon\n");
        assertThat(outcome.startFailure().outputRef()).as("nowhere to store it").isNull();
        assertThat(outcome.startFailure().whereToRead()).isEmpty();
    }

    /** A handle that can only say "not running" still ends the wait and says it ended. */
    @Test
    void aHandleThatCannotSayHowItsCommandEndedStillSaysThatItEnded() {
        Place place = new Place(null, "") {
            @Override public ServiceHandle startService(String command) {
                return new ServiceHandle() {
                    @Override public boolean isAlive() {
                        return false;
                    }
                    @Override public String outputSoFar() {
                        return "";
                    }
                    @Override public void close() { }
                };
            }
        };

        JourneyRunner.Outcome outcome = JourneyRunner.run(() -> place,
            contract("java -jar app.jar", 300), List.of(journey()), BlobSink.NONE,
            new StringBuilder());

        assertThat(outcome.didNotStart()).contains("the application exited (no exit code was "
            + "recorded) after");
        assertThat(outcome.startFailure().output().isEmpty()).isTrue();
    }

    // ------------------------------------------------------------------ the real thing

    @TempDir
    Path work;

    /**
     * A real container: a command that prints to both streams and exits with a code of its own
     * is seen to have ended at once, although the watchdog that would stop a runaway service
     * is still asleep in the container; and one that runs and never listens is waited for and
     * said to be running.
     */
    @Test
    @RunsWhen(value = Need.DOCKER, image = UI_IMAGE)
    void inARealContainerAnExitIsSeenAtOnceWithItsCodeAndBothStreams() throws Exception {
        assertThat(HostExecution.allowedBy()).isEmpty();
        Path tree = Files.createDirectories(work.resolve("built"));
        Files.writeString(tree.resolve("readme.txt"), "a tree");
        BuildBoxes boxes = BuildBoxes.of(new DockerSandboxManager(
            System.getProperty("swarmcoder.sandbox.image", "swarmcoder-worker:latest"), 2, 4,
            System.getProperty("swarmcoder.sandbox.dockerHost"),
            Path.of(System.getProperty("user.home"), ".m2").toString()));
        JourneyRunner.Starts starts = boxes.cleanStarts(tree, "A journey of this test");
        StringBuilder log = new StringBuilder();

        long before = System.nanoTime();
        JourneyRunner.Outcome ended = JourneyRunner.run(starts,
            contract("echo said on out; echo said on err >&2; sleep 2; exit 7", 240),
            List.of(journey()), BlobSink.NONE, log);
        long seconds = (System.nanoTime() - before) / 1_000_000_000L;

        assertThat(ended.couldNotRun()).as(log.toString()).isNull();
        assertThat(ended.didNotStart()).as(log.toString())
            .contains("the application exited with code 7 after");
        assertThat(seconds).as("the 240 seconds are not waited out").isLessThan(120);
        assertThat(ended.startFailure().exited().exitCode()).isEqualTo(7);
        assertThat(ended.startFailure().exited().afterSeconds()).isBetween(1L, 30L);
        assertThat(ended.startFailure().output().text())
            .contains("said on out").contains("said on err");

        JourneyRunner.Outcome running = JourneyRunner.run(starts,
            contract("echo waiting; sleep 600", 5), List.of(journey()), BlobSink.NONE, log);

        assertThat(running.didNotStart()).as(log.toString())
            .contains("within 5 seconds").contains("still running when the wait ended");
        assertThat(running.startFailure().exited()).isNull();
        assertThat(running.startFailure().output().text()).contains("waiting");
    }
}
