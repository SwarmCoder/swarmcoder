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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documentation that does not come from a folder goes through the SAME index and the same ranking.
 *
 * <p><b>What it was.</b> Two side channels and one blind spot. Library documentation fetched from
 * the documentation server went into a separate Lucene index that stored each whole reply as ONE
 * document, was consulted only after the folder search had already failed, returned the top hit
 * whole, and — a TODO in the code said so — did not filter by which library it came from, so one
 * library could answer for another. And a documentation SITE could not be a reference source at
 * all: the web fetcher existed but only the chat researcher could use it, nothing it fetched was
 * indexed, and a project's reference sources were folders only. A project whose framework had a
 * documentation site but no checkout had, to a worker, no documentation.
 *
 * <p><b>Workers still never touch the network.</b> Everything here happens in the orchestrator
 * process while the index is built; a worker sees an indexed page like any other.
 */
class ExternalDocumentationReachesTheIndexTest {

    @TempDir
    Path repo;
    @TempDir
    Path cache;

    HttpServer server;
    String base;
    final AtomicInteger pageRequests = new AtomicInteger();

    private static final String SAVING = """
        <html><head><title>Saving</title></head><body><main>
        <h1>Saving data</h1>
        <p>How to persist your object graph.</p>
        <h2>The basics</h2>
        <p>Inject the storage manager and read the root from it.</p>
        <pre>DataRoot root = (DataRoot) storage.root();</pre>
        <h2>The rule: one call, one save</h2>
        <p>Call storeAll to write two objects in one commit.</p>
        </main></body></html>
        """;

    private static final String ROUTING = """
        <html><body><main>
        <h1>Routing</h1>
        <p>How a URL reaches a view.</p>
        <h2>Declaring a route</h2>
        <p>Annotate the view with Route and give it a path.</p>
        </main></body></html>
        """;

    @BeforeEach
    void startTheSite() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sitemap.xml", exchange -> respond(exchange, 200,
            "application/xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset><url><loc>%s/saving/</loc></url>
                <url><loc>%s/routing/</loc></url></urlset>
                """.formatted(base(), base())));
        server.createContext("/saving/", exchange -> {
            pageRequests.incrementAndGet();
            exchange.getResponseHeaders().add("ETag", "\"saving-1\"");
            if ("\"saving-1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                respond(exchange, 304, "text/html", "");
                return;
            }
            respond(exchange, 200, "text/html", SAVING);
        });
        server.createContext("/routing/", exchange -> {
            pageRequests.incrementAndGet();
            respond(exchange, 200, "text/html", ROUTING);
        });
        server.start();
        base = base();
    }

    private String base() {
        return "http://127.0.0.1:" + (server == null ? 0 : server.getAddress().getPort());
    }

    @AfterEach
    void stopTheSite() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String type, String body)
        throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, status == 304 ? -1 : bytes.length);
        if (status != 304) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    /** A site is crawled from its own sitemap and its headings survive into sections. */
    @Test
    void aDocumentationSiteIsCrawledFromItsSitemapAndSplitIntoSections() {
        WebDocs.Site site = new WebDocs(cache.resolve("web")).crawl(base);

        assertThat(site.pages()).hasSize(2);
        assertThat(site.pages().get(0).url()).isEqualTo(base + "/saving/");
        assertThat(site.pages().get(0).markdown())
            .contains("# Saving data")
            .contains("## The basics")
            .contains("DataRoot root = (DataRoot) storage.root();");
        assertThat(KnowledgeCurator.sectionsOf(site.pages().get(0).url(),
            site.pages().get(0).markdown()))
            .extracting(KnowledgeCurator.DocSection::heading)
            .contains("Saving data", "The basics", "The rule: one call, one save");
    }

    /** A page that has not changed is asked for conditionally and comes back from the cache. */
    @Test
    void aPageThatHasNotChangedIsNotFetchedAgain() {
        WebDocs web = new WebDocs(cache.resolve("web"));
        web.crawl(base);
        int afterFirst = pageRequests.get();

        WebDocs.Site again = web.crawl(base);

        assertThat(again.pages()).hasSize(2);
        assertThat(again.pages().get(0).markdown()).contains("## The basics");
        assertThat(pageRequests.get())
            .as("the pages were asked for again, but the unchanged one answered 304")
            .isGreaterThan(afterFirst);
    }

    /** The site's pages are searched exactly like a folder's, and the answer names the page. */
    @Test
    void aQuestionIsAnsweredFromTheSiteThroughTheOrdinaryRanking() throws Exception {
        Librarian librarian = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(), repo, null, cache, null, null, List.of(base));
        awaitCrawl(librarian, base + "/saving/");

        String answer = librarian.lookupApi("storage manager root storeAll one commit");

        assertThat(answer)
            .contains(base + "/saving/")
            .contains("storeAll");
        assertThat(librarian.curator().documentationMap(2_000))
            .contains(base + "/saving/ — Saving data: How to persist your object graph.");
    }

    /** A library server's reply becomes sections, tagged with the library it came from. */
    @Test
    void aLibraryServersReplyIsSplitIntoSectionsAndTaggedWithItsLibrary() {
        Librarian librarian = new Librarian(new Context7Client("http://localhost:1/sse"), null,
            List.of(), repo, null, cache);
        librarian.curator().indexLibraryDocs("/demo/stack/v2.1", "2.1", """
            # DemoStack

            ## Saving with the storage manager
            Call zzzstoreall to write several objects in one commit.

            ## Routing a view
            Annotate the view with zzzroute.
            """);

        assertThat(librarian.curator().holdsLibraryDocs("/demo/stack/v2.1", "2.1")).isTrue();
        assertThat(librarian.lookupApi("zzzstoreall one commit"))
            .as("the section that answers, not the whole reply")
            .contains("context7/demo.stack.v2.1@2.1.md")
            .contains("Saving with the storage manager")
            .doesNotContain("zzzroute");
        assertThat(Librarian.libraryVersion("/demo/stack/v2.1")).isEqualTo("2.1");
        assertThat(Librarian.libraryVersion("/demo/stack")).isEqualTo("latest");
    }

    /** One library's documentation never answers for another's. */
    @Test
    void oneLibrarysDocumentationDoesNotAnswerForAnother() throws Exception {
        DocsIndex docs = new DocsIndex(cache.resolve("docsindex"));
        docs.indexDocs("/demo/alpha", "# Alpha\nThe zzzshared symbol belongs to alpha.");
        docs.indexDocs("/demo/beta", "# Beta\nThe zzzshared symbol belongs to beta.");

        assertThat(docs.lookupApi("/demo/beta", "zzzshared")).get()
            .asString().contains("belongs to beta").doesNotContain("belongs to alpha");
        assertThat(docs.lookupApi("/demo/alpha", "zzzshared")).get()
            .asString().contains("belongs to alpha");
        assertThat(docs.lookupApi("", "zzzshared"))
            .as("a blank id still means any library")
            .isPresent();
    }

    /** A site that is not reachable costs a log line, not a run. */
    @Test
    void anUnreachableSiteIsEmptyRatherThanFatal() {
        WebDocs.Site dead = new WebDocs(cache.resolve("web")).crawl("http://127.0.0.1:1");
        assertThat(dead.pages()).isEmpty();

        WebDocs.Site notAUrl = new WebDocs(cache.resolve("web")).crawl("C:/not/a/site");
        assertThat(notAUrl.pages()).isEmpty();
    }

    /** The crawl runs in the background; give it a moment before asking what it found. */
    private void awaitCrawl(Librarian librarian, String address) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (librarian.curator().documentationMap(4_000).contains(address)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the documentation site was never indexed: " + address);
    }

}
