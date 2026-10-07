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

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.net.URLDecoder;

/**
 * The Researcher's window on the open web (author approval 2026-07-14 — supersedes the
 * spec §18 no-web rule for the RESEARCHER ONLY; workers remain offline). Interface so
 * tests script it; the default implementation is DuckDuckGo's HTML endpoint (no API key)
 * plus a capped, tag-stripped URL fetch. Everything best-effort: errors come back as
 * strings the model can react to, never as exceptions.
 */
public interface WebAccess {

    /** Search results as "title — url — snippet" lines, or an error string. */
    String search(String query);

    /** A page's readable text (tags stripped, capped), or an error string. */
    String fetch(String url);

    static WebAccess standard() {
        return new Standard();
    }

    final class Standard implements WebAccess {

        private static final int MAX_PAGE_CHARS = 12_000;
        private static final int MAX_RESULTS = 8;
        private static final Pattern RESULT = Pattern.compile(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
        private static final Pattern SNIPPET = Pattern.compile(
            "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);

        private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

        @Override
        public String search(String query) {
            try {
                String body = get("https://html.duckduckgo.com/html/?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8));
                Matcher links = RESULT.matcher(body);
                Matcher snippets = SNIPPET.matcher(body);
                StringBuilder sb = new StringBuilder();
                int count = 0;
                while (links.find() && count < MAX_RESULTS) {
                    String url = links.group(1);
                    // DDG wraps targets in a redirect: uddg=<encoded url>.
                    int uddg = url.indexOf("uddg=");
                    if (uddg >= 0) {
                        String encoded = url.substring(uddg + 5);
                        int amp = encoded.indexOf('&');
                        url = URLDecoder.decode(
                            amp > 0 ? encoded.substring(0, amp) : encoded, StandardCharsets.UTF_8);
                    }
                    String snippet = snippets.find() ? strip(snippets.group(1)) : "";
                    sb.append(strip(links.group(2))).append(" — ").append(url)
                      .append(" — ").append(snippet).append('\n');
                    count++;
                }
                return sb.isEmpty() ? "no results for: " + query : sb.toString();
            } catch (Exception e) {
                return "error: web search failed: " + e.getMessage();
            }
        }

        @Override
        public String fetch(String url) {
            try {
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return "error: only http(s) URLs can be fetched";
                }
                String body = get(url);
                String text = strip(body);
                return text.length() <= MAX_PAGE_CHARS ? text
                    : text.substring(0, MAX_PAGE_CHARS) + "\n… (page truncated)";
            } catch (Exception e) {
                return "error: fetch failed: " + e.getMessage();
            }
        }

        private String get(String url) throws Exception {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "SwarmCoder-Researcher/1.0")
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            return response.body();
        }

        /** Tag-strip + entity-decode + whitespace-collapse — readable text, not a DOM. */
        static String strip(String html) {
            String text = html
                .replaceAll("(?is)<(script|style|noscript)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br[^>]*>|</p>|</div>|</li>|</h[1-6]>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#x27;", "'").replace("&nbsp;", " ");
            return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n\\s*\\n+", "\n").strip();
        }
    }
}
