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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A journey is made on the application as the tree gives it, never on what an earlier journey
 * left (DEVELOPER_CORRECTIONS section 77). Live run 104: an application that keeps its books in
 * a folder of the tree was started a second time in the same tree for the corrected journey; the
 * book the first attempt had saved was still listed, the journey failed on it, and a run whose
 * application was correct was stopped.
 */
class EveryJourneyStartsOnWhatTheTreeGivesTest {

    private static final String UI_IMAGE = "swarmcoder-worker-ui:latest";

    private static final VerifySpec CONTRACT = new VerifySpec("maven", List.of(), List.of(),
        List.of(), List.of(), List.of(), null, 0,
        new VerifySpec.BrowserSpec("node server.js {PORT}", null, 60, List.of(), 0));

    /** Fails on a second making in the same state: the book it adds is then there from the start. */
    private static JourneyFile.Journey addsABook(String name) {
        JourneyFile.Read read = JourneyFile.read("app/src/test/java/swarm/accept/" + name
            + ".journey.yaml", """
            journey: %s
            steps:
              - expectHidden: "#rows >> text=Dune"
              - fill: "#title"
                value: "Dune"
              - click: "#save"
              - expectVisible: "#rows >> text=Dune"
            """.formatted(name));
        assertThat(read.ok()).as(read.objection()).isTrue();
        return read.journey();
    }

    // ------------------------------------------------------------------ no container needed

    /** What the tree holds as built, and every place a journey was made in. */
    private static final class Tree implements JourneyRunner.Starts {
        final List<String> rowsAsBuilt = new ArrayList<>();
        final List<Place> opened = new ArrayList<>();
        int open;

        @Override
        public JourneyRunner.Start open() {
            assertThat(open).as("a place is closed before the next is opened").isZero();
            open++;
            Place place = new Place(this);
            opened.add(place);
            return place;
        }
    }

    /**
     * A container on a copy of the tree, with an application that saves what a journey adds.
     * The browser's answer is this fake's: a journey fails when its book is already listed.
     */
    private static final class Place implements JourneyRunner.Start, ExecTarget {
        private final Tree tree;
        final List<String> rows;
        int started;
        boolean closed;

        Place(Tree tree) {
            this.tree = tree;
            this.rows = new ArrayList<>(tree.rowsAsBuilt);
        }

        @Override public ExecTarget target() {
            return this;
        }

        @Override public void close() {
            closed = true;
            tree.open--;
        }

        @Override public ExecResult exec(String command, int timeoutSeconds) {
            String out = "";
            if (command.contains("SC_BROWSER_PRESENT")) {
                out = "SC_BROWSER_PRESENT";
            } else if (command.contains("SC_READY")) {
                out = "SC_READY";
            } else if (command.contains("| base64 -d | node ")) {
                boolean there = rows.contains("Dune");
                rows.add("Dune");
                out = "<<<SC-BROWSER-JSON{\"ok\":true,\"pages\":[{\"url\":\"/\",\"loaded\":true,"
                    + "\"consoleErrors\":[],\"assertions\":[{\"selector\":"
                    + "\"step 1: expect hidden #rows >> text=Dune\",\"passed\":" + !there
                    + ",\"message\":\"" + (there ? "it was visible" : "") + "\"}]}]}"
                    + "SC-BROWSER-JSON>>>";
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
            started++;
            return new ServiceHandle() {
                @Override public boolean isAlive() {
                    return true;
                }
                @Override public String outputSoFar() {
                    return "";
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
    void whatOneJourneySavedIsNotThereForTheNextNorForTheSameJourneyMadeAgain() {
        Tree tree = new Tree();
        JourneyFile.Journey first = addsABook("a-book-is-added");
        JourneyFile.Journey second = addsABook("a-book-is-added-from-the-menu");

        // Several journeys of one final integration.
        JourneyRunner.Outcome together = JourneyRunner.run(tree, CONTRACT, List.of(first, second),
            BlobSink.NONE, new StringBuilder());
        // The same journey made again: a correction after review, a second review,
        // check_journey, the comparison on the start tree - all of them come through here.
        JourneyRunner.Outcome again = JourneyRunner.run(tree, CONTRACT, List.of(first),
            BlobSink.NONE, new StringBuilder());

        assertThat(together.made()).isTrue();
        assertThat(together.failed()).as("the second journey does not see the first one's book")
            .isEmpty();
        assertThat(together.results()).extracting(r -> r.journey().path())
            .containsExactly(first.path(), second.path());
        assertThat(again.failed()).as("made again, it does not see its own earlier book").isEmpty();
        assertThat(tree.opened).as("one place per journey made").hasSize(3);
        assertThat(tree.opened).allSatisfy(place -> {
            assertThat(place.started).as("the application is started once in a place").isEqualTo(1);
            assertThat(place.closed).isTrue();
        });
        assertThat(tree.rowsAsBuilt).as("nothing was written to the tree itself").isEmpty();
    }

    @Test
    void withNoCleanPlaceTheJourneyIsNotMadeAndThatIsSaid() {
        JourneyRunner.Outcome outcome = JourneyRunner.run(() -> {
            throw new DockerSandboxManager.SandboxException("the tree could not be copied");
        }, CONTRACT, List.of(addsABook("a-book-is-added")), BlobSink.NONE, new StringBuilder());

        assertThat(outcome.made()).isFalse();
        assertThat(outcome.couldNotRun()).contains("was not made")
            .contains("the tree could not be copied");
    }

    // ------------------------------------------------------------------ the real thing

    @TempDir
    Path work;

    /**
     * A real application that keeps its books in {@code ./data} beside it, a real browser, real
     * containers: the case of run 104. It also writes a file in its home folder, which a copy of
     * the tree alone would not throw away.
     */
    @Test
    @RunsWhen(value = Need.DOCKER, image = UI_IMAGE)
    void anApplicationThatKeepsItsDataOnDiskIsMetEmptyByEveryJourney() throws Exception {
        assertThat(HostExecution.allowedBy()).isEmpty();
        Path tree = Files.createDirectories(work.resolve("built"));
        Files.writeString(tree.resolve("server.js"), """
            const http=require('http'),fs=require('fs'),os=require('os'),path=require('path');
            const store='data/books.txt', seen=path.join(os.homedir(),'seen-before');
            const stale=fs.existsSync(seen); fs.writeFileSync(seen,'x');
            const rows=()=>fs.existsSync(store)?fs.readFileSync(store,'utf8').split('\\n').filter(Boolean):[];
            http.createServer((q,s)=>{
              const u=new URL(q.url,'http://x');
              if(u.pathname==='/add'){
                fs.mkdirSync('data',{recursive:true});
                fs.appendFileSync(store,u.searchParams.get('title')+'\\n');
                s.statusCode=302;s.setHeader('location','/');return s.end()}
              s.setHeader('content-type','text/html; charset=utf-8');
              s.end('<html><body><h1>Books</h1><form action="/add"><input id="title" name="title">'
                +'<button id="save">Save</button></form><ul id="rows">'
                +rows().map(r=>'<li>'+r+'</li>').join('')+(stale?'<li>Dune (home)</li>':'')
                +'</ul></body></html>')
            }).listen(Number(process.argv[2]),'127.0.0.1');
            """);
        BuildBoxes boxes = BuildBoxes.of(new DockerSandboxManager(
            System.getProperty("swarmcoder.sandbox.image", "swarmcoder-worker:latest"), 2, 4,
            System.getProperty("swarmcoder.sandbox.dockerHost"),
            Path.of(System.getProperty("user.home"), ".m2").toString()));
        JourneyRunner.Starts starts = boxes.cleanStarts(tree, "A journey of this test");
        StringBuilder log = new StringBuilder();

        JourneyRunner.Outcome together = JourneyRunner.run(starts, CONTRACT,
            List.of(addsABook("a-book-is-added"), addsABook("a-book-is-added-from-the-menu")),
            BlobSink.NONE, log);
        JourneyRunner.Outcome again = JourneyRunner.run(starts, CONTRACT,
            List.of(addsABook("a-book-is-added")), BlobSink.NONE, log);

        assertThat(together.couldNotRun()).as(log.toString()).isNull();
        assertThat(together.didNotStart()).as(log.toString()).isNull();
        assertThat(together.results()).hasSize(2);
        assertThat(together.failed()).as(log.toString()).isEmpty();
        assertThat(again.failed()).as(log.toString()).isEmpty();
        assertThat(again.results()).hasSize(1);
        assertThat(tree.resolve("data")).as("the application never wrote to the tree itself")
            .doesNotExist();
        try (Stream<Path> left = Files.list(work)) {
            assertThat(left.map(p -> p.getFileName().toString()).toList())
                .as("every copy is removed with its container").containsExactly("built");
        }
    }
}
