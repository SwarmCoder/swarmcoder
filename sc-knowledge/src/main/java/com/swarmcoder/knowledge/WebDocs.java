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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A documentation SITE as a reference source: the pages of {@code https://docs.example.com} pulled
 * once, turned into markdown-ish text, and put into the same index as a folder's documents.
 *
 * <p><b>Why a site and not a folder.</b> Not every framework a project depends on is checked out
 * next to it. The published documentation usually is on the web, and until now a worker could
 * reach it through nothing: the web fetcher existed but only the chat researcher could use it,
 * nothing fetched was indexed, and reference roots were folders only. So a project whose framework
 * had a documentation site had, from the worker's point of view, no documentation at all.
 *
 * <p><b>Fetched in the app, never in a worker.</b> Workers stay offline; this runs in the
 * orchestrator process while the index is built, and what a worker sees is an indexed page like
 * any other.
 *
 * <p><b>Fetched once.</b> Every page is cached on disk under the index folder with its
 * {@code ETag} and {@code Last-Modified}, and a refresh asks the server conditionally, so a site
 * that has not changed costs one 304 per page and no re-indexing.
 */
final class WebDocs {

    private static final Logger log = LoggerFactory.getLogger(WebDocs.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PAGES = 300;
    private static final int MAX_PAGE_BYTES = 400_000;

    /** One page of a site: where it came from and what it says. */
    record Page(String url, String markdown) {}

    /** A site's pages plus the version to call them. */
    record Site(String baseUrl, String version, List<Page> pages) {}

    private final HttpClient client;
    private final Path cacheRoot;

    WebDocs(Path cacheRoot) {
        this(cacheRoot, HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
    }

    WebDocs(Path cacheRoot, HttpClient client) {
        this.cacheRoot = cacheRoot;
        this.client = client;
    }

    /**
     * Every page of the site at {@code baseUrl}, from its sitemap, its MkDocs search index, or —
     * failing both — the base page alone. Never throws: an unreachable site is an empty result and
     * one log line, because a documentation site being down must not stop a run.
     */
    Site crawl(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.strip();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            log.warn("Not a documentation site URL, ignored: {}", baseUrl);
            return new Site(base, "unknown", List.of());
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        Path cache = cacheRoot.resolve(hash(base));
        Map<String, Cached> cached = readCache(cache);
        List<String> urls = discover(base);
        List<Page> pages = new ArrayList<>();
        for (String url : urls) {
            Page page = page(url, cache, cached);
            if (page != null) {
                pages.add(page);
            }
        }
        writeCache(cache, cached);
        return new Site(base, versionOf(base, pages), pages);
    }

    // --- discovery ------------------------------------------------------------------------------

    private static final Pattern SITEMAP_LOC = Pattern.compile("<loc>\\s*([^<\\s]+)\\s*</loc>");

    /** The site's own list of its pages: sitemap.xml, then MkDocs' search index, then the base. */
    private List<String> discover(String base) {
        Set<String> urls = new LinkedHashSet<>();
        String sitemap = body(base + "/sitemap.xml");
        if (sitemap != null && sitemap.contains("<loc>")) {
            Matcher matcher = SITEMAP_LOC.matcher(sitemap);
            while (matcher.find() && urls.size() < MAX_PAGES) {
                urls.add(matcher.group(1).strip());
            }
        }
        if (urls.isEmpty()) {
            String search = body(base + "/search/search_index.json");
            if (search != null) {
                try {
                    JsonNode docs = JSON.readTree(search).path("docs");
                    for (JsonNode entry : docs) {
                        String location = entry.path("location").asText("");
                        int anchor = location.indexOf('#');
                        String page = anchor < 0 ? location : location.substring(0, anchor);
                        if (!page.isBlank() && urls.size() < MAX_PAGES) {
                            urls.add(base + "/" + page);
                        }
                    }
                } catch (Exception e) {
                    log.debug("MkDocs search index at {} unreadable: {}", base, e.getMessage());
                }
            }
        }
        if (urls.isEmpty()) {
            urls.add(base + "/");
        }
        return List.copyOf(urls);
    }

    /**
     * A version for the site. A path segment that looks like one ({@code /1.4/}, {@code /v2/}) is
     * the answer when there is one — versioned documentation sites put it there. Otherwise the
     * pages themselves are the version: a hash of what was fetched, so that a site which changes
     * gets a new index rather than a mixed one.
     */
    private static String versionOf(String base, List<Page> pages) {
        Matcher matcher = Pattern.compile("/v?(\\d+(?:\\.\\d+){0,2})(?:/|$)").matcher(base);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (pages.isEmpty()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        for (Page page : pages) {
            sb.append(page.url()).append(':').append(page.markdown().length()).append('\n');
        }
        return "site-" + hash(sb.toString()).substring(0, 8);
    }

    // --- fetching, with a conditional request -------------------------------------------------

    private record Cached(String etag, String lastModified, String file) {}

    private Page page(String url, Path cache, Map<String, Cached> cached) {
        Cached known = cached.get(url);
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "SwarmCoder-Librarian/1.0")
                .GET();
            if (known != null && known.etag() != null && !known.etag().isBlank()) {
                request.header("If-None-Match", known.etag());
            } else if (known != null && known.lastModified() != null
                && !known.lastModified().isBlank()) {
                request.header("If-Modified-Since", known.lastModified());
            }
            HttpResponse<String> response =
                client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 304 && known != null) {
                String markdown = readCachedPage(cache, known.file());
                return markdown.isBlank() ? null : new Page(url, markdown);
            }
            if (response.statusCode() >= 400) {
                log.debug("Documentation page {} returned HTTP {}", url, response.statusCode());
                return null;
            }
            String html = response.body();
            String markdown = toMarkdown(
                html.length() > MAX_PAGE_BYTES ? html.substring(0, MAX_PAGE_BYTES) : html);
            if (markdown.isBlank()) {
                return null;
            }
            String file = hash(url) + ".md";
            Files.createDirectories(cache);
            Files.writeString(cache.resolve(file), markdown, StandardCharsets.UTF_8);
            cached.put(url, new Cached(header(response, "ETag"),
                header(response, "Last-Modified"), file));
            return new Page(url, markdown);
        } catch (Exception e) {
            log.debug("Could not fetch documentation page {}: {}", url, e.getMessage());
            if (known != null) {
                String markdown = readCachedPage(cache, known.file());
                return markdown.isBlank() ? null : new Page(url, markdown);
            }
            return null;
        }
    }

    private String body(String url) {
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "SwarmCoder-Librarian/1.0")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 400 ? null : response.body();
        } catch (Exception e) {
            return null;
        }
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    // --- HTML to something a markdown splitter understands --------------------------------------

    private static final Pattern HEADING =
        Pattern.compile("(?is)<h([1-6])[^>]*>(.*?)</h\\1>");

    /**
     * The readable text of a page with its headings kept as markdown, so the same splitter that
     * cuts a {@code .md} file into sections cuts a web page into sections. Headings are the whole
     * point: a section is what a worker can be given, and without them a documentation page is one
     * undifferentiated wall that always loses on length.
     */
    static String toMarkdown(String html) {
        String body = html;
        int main = body.toLowerCase(Locale.ROOT).indexOf("<main");
        if (main >= 0) {
            body = body.substring(main);
        }
        body = body.replaceAll("(?is)<(script|style|noscript|nav|footer|header)[^>]*>.*?</\\1>", "\n");
        Matcher matcher = HEADING.matcher(body);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String hashes = "#".repeat(Integer.parseInt(matcher.group(1)));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                "\n" + hashes + " " + strip(matcher.group(2)) + "\n"));
        }
        matcher.appendTail(sb);
        String text = sb.toString()
            .replaceAll("(?i)<br[^>]*>|</p>|</div>|</li>|</tr>", "\n")
            .replaceAll("(?is)<pre[^>]*>", "\n```\n")
            .replaceAll("(?is)</pre>", "\n```\n");
        return strip(text);
    }

    private static String strip(String html) {
        String text = html.replaceAll("<[^>]+>", " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
            .replace("&nbsp;", " ");
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
            .replaceAll("\\n[ \\t]+", "\n")
            .replaceAll("\\n{3,}", "\n\n").strip();
    }

    // --- the on-disk cache ----------------------------------------------------------------------

    private Map<String, Cached> readCache(Path cache) {
        Map<String, Cached> map = new LinkedHashMap<>();
        Path index = cache.resolve("pages.json");
        if (!Files.isRegularFile(index)) {
            return map;
        }
        try {
            JsonNode root = JSON.readTree(index.toFile());
            root.fields().forEachRemaining(entry -> map.put(entry.getKey(),
                new Cached(entry.getValue().path("etag").asText(""),
                    entry.getValue().path("lastModified").asText(""),
                    entry.getValue().path("file").asText(""))));
        } catch (Exception e) {
            log.debug("Documentation cache at {} unreadable: {}", cache, e.getMessage());
        }
        return map;
    }

    private void writeCache(Path cache, Map<String, Cached> pages) {
        try {
            Files.createDirectories(cache);
            var root = JSON.createObjectNode();
            pages.forEach((url, entry) -> {
                var node = root.putObject(url);
                node.put("etag", entry.etag() == null ? "" : entry.etag());
                node.put("lastModified", entry.lastModified() == null ? "" : entry.lastModified());
                node.put("file", entry.file());
            });
            Files.writeString(cache.resolve("pages.json"), root.toPrettyString(),
                StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.debug("Could not write documentation cache at {}: {}", cache, e.getMessage());
        }
    }

    private static String readCachedPage(Path cache, String file) {
        try {
            Path path = cache.resolve(file);
            return Files.isRegularFile(path) ? Files.readString(path) : "";
        } catch (Exception e) {
            return "";
        }
    }

    static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(Integer.toHexString((bytes[i] >> 4) & 0xf))
                   .append(Integer.toHexString(bytes[i] & 0xf));
            }
            return hex.toString();
        } catch (Exception e) {
            return "0000000000000000";
        }
    }
}
