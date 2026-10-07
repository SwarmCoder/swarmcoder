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
package com.swarmcoder.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The malformed shapes a paid endpoint actually sent SwarmCoder on 2026-09-03: run
 * {@code 9e69a8ec…} parked with no acceptance tests because the test author's reply was a JSON
 * <em>string containing escaped JSON</em> — {@code {\"files\": [...]}} with the outer quotes
 * missing — and the same morning the architect's revision reply was rejected outright for
 * carrying one harmless extra field. Every shape here is a reply {@link LlmJson#parse} used to
 * throw on and must now accept.
 */
class LlmJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    static class Files {
        public List<Entry> files;
    }
    static class Entry {
        public String path;
        public String content;
    }

    @Test
    void parsesAPlainObject() throws Exception {
        Files parsed = LlmJson.parse(mapper, "{\"files\":[{\"path\":\"a\",\"content\":\"b\"}]}", Files.class);
        assertThat(parsed.files).hasSize(1);
        assertThat(parsed.files.get(0).path).isEqualTo("a");
    }

    /** The exact shape from the 07:23:40 test-author failure: escaped JSON with no wrapping quotes. */
    @Test
    void unwrapsAnEscapedBodyWithNoOuterQuotes() throws Exception {
        String reply = "{\\\"files\\\": [{\\\"path\\\":\\\"src/test/java/swarm/accept/T.java\\\","
            + "\\\"content\\\":\\\"class T {}\\\"}]}";
        Files parsed = LlmJson.parse(mapper, reply, Files.class);
        assertThat(parsed.files).hasSize(1);
        assertThat(parsed.files.get(0).path).isEqualTo("src/test/java/swarm/accept/T.java");
        assertThat(parsed.files.get(0).content).isEqualTo("class T {}");
    }

    /** Same shape, but properly wrapped in real outer quotes — the object as a JSON string value. */
    @Test
    void unwrapsAnEscapedBodyWithOuterQuotes() throws Exception {
        String inner = "{\"files\":[{\"path\":\"a\",\"content\":\"b\"}]}";
        String reply = mapper.writeValueAsString(inner); // produces "{\"files\":...}"
        Files parsed = LlmJson.parse(mapper, reply, Files.class);
        assertThat(parsed.files).hasSize(1);
        assertThat(parsed.files.get(0).path).isEqualTo("a");
    }

    @Test
    void stripsAJsonCodeFence() throws Exception {
        String reply = "```json\n{\"files\":[{\"path\":\"a\",\"content\":\"b\"}]}\n```";
        Files parsed = LlmJson.parse(mapper, reply, Files.class);
        assertThat(parsed.files).hasSize(1);
    }

    @Test
    void stripsABareCodeFence() throws Exception {
        String reply = "```\n{\"files\":[{\"path\":\"a\",\"content\":\"b\"}]}\n```";
        Files parsed = LlmJson.parse(mapper, reply, Files.class);
        assertThat(parsed.files).hasSize(1);
    }

    @Test
    void toleratesALeadingProseLine() throws Exception {
        String reply = "Sure, here are the tests:\n{\"files\":[{\"path\":\"a\",\"content\":\"b\"}]}";
        Files parsed = LlmJson.parse(mapper, reply, Files.class);
        assertThat(parsed.files).hasSize(1);
    }

    /** The exact shape from the 07:14:44 architect-revision failure: one field the schema lacks. */
    @Test
    void ignoresAnUnknownField() throws Exception {
        String reply = "{\"id\":\"abc-123\",\"files\":[{\"path\":\"a\",\"content\":\"b\"}]}";
        Files parsed = LlmJson.parse(mapper, reply, Files.class);
        assertThat(parsed.files).hasSize(1);
    }

    @Test
    void stillFailsOnGenuineGarbage() {
        assertThatThrownBy(() -> LlmJson.parse(mapper, "not json at all, no braces", Files.class))
            .isInstanceOf(java.io.IOException.class);
    }

    /**
     * {@link LlmJson#readTree} — the sc-console guided flows read a raw tree rather than binding
     * onto a class, since the field they want ("proposals", "questions") is one of several a reply
     * may carry. Same tolerant extraction, just no POJO at the end of it.
     */
    @Test
    void readTreeToleratesTheSameShapesAsParse() throws Exception {
        JsonNode plain = LlmJson.readTree(mapper, "{\"proposals\":[{\"title\":\"a\"}]}");
        assertThat(plain.path("proposals")).hasSize(1);

        JsonNode fenced = LlmJson.readTree(mapper,
            "```json\n{\"proposals\":[{\"title\":\"a\"}]}\n```");
        assertThat(fenced.path("proposals")).hasSize(1);
    }

    @Test
    void readTreeStillFailsOnGenuineGarbage() {
        assertThatThrownBy(() -> LlmJson.readTree(mapper, "not json at all, no braces"))
            .isInstanceOf(java.io.IOException.class);
    }

    // --- wrapAsField ------------------------------------------------------------------------------

    /**
     * {@link LlmJson#wrapAsField} — the shape tolerance {@code RequirementsIntake} needs for a
     * reply that names its array something else, sends it bare, or drops the envelope entirely.
     * Kept here rather than in the console module because it is generic tree-shape handling, not
     * anything specific to proposals or questions.
     */
    @Test
    void wrapAsFieldLeavesTheCanonicalFieldAlone() throws Exception {
        JsonNode root = mapper.readTree("{\"proposals\":[{\"title\":\"a\"}]}");
        JsonNode wrapped = LlmJson.wrapAsField(mapper, root, "proposals", "items");
        assertThat(wrapped.path("proposals")).hasSize(1);
    }

    @Test
    void wrapAsFieldAcceptsAnAliasFieldName() throws Exception {
        JsonNode root = mapper.readTree("{\"items\":[{\"title\":\"a\"},{\"title\":\"b\"}]}");
        JsonNode wrapped = LlmJson.wrapAsField(mapper, root, "proposals", "items");
        assertThat(wrapped.path("proposals")).hasSize(2);
        assertThat(wrapped.path("proposals").get(0).path("title").asText()).isEqualTo("a");
    }

    @Test
    void wrapAsFieldAcceptsABareArray() throws Exception {
        JsonNode root = mapper.readTree("[{\"title\":\"a\"}]");
        JsonNode wrapped = LlmJson.wrapAsField(mapper, root, "proposals", "items");
        assertThat(wrapped.path("proposals")).hasSize(1);
    }

    @Test
    void wrapAsFieldFindsNothingInAnEmptyObject() throws Exception {
        JsonNode root = mapper.readTree("{}");
        assertThat(LlmJson.wrapAsField(mapper, root, "proposals", "items")).isNull();
    }

    /**
     * {@code wrapAsField} deliberately does NOT wrap a lone object it is handed — see its javadoc.
     * A model that sends the array's own field name is a shape difference and is accepted; an
     * arbitrary standalone object is not, because {@link LlmJson#readTree}'s own retry-past-a-bad-
     * brace can hand back exactly this shape from a fragment of a reply that, as a whole, never
     * parsed — and this method has no way to tell the two apart.
     */
    @Test
    void wrapAsFieldDoesNotWrapALoneObject() throws Exception {
        JsonNode root = mapper.readTree("{\"title\":\"a\"}");
        assertThat(LlmJson.wrapAsField(mapper, root, "proposals", "items")).isNull();
    }

    // --- wrapSingleAsField / readWholeValue --------------------------------------------------------

    @Test
    void wrapSingleAsFieldWrapsANonEmptyObject() throws Exception {
        JsonNode root = mapper.readTree("{\"title\":\"a\"}");
        JsonNode wrapped = LlmJson.wrapSingleAsField(mapper, root, "proposals");
        assertThat(wrapped.path("proposals")).hasSize(1);
        assertThat(wrapped.path("proposals").get(0).path("title").asText()).isEqualTo("a");
    }

    @Test
    void wrapSingleAsFieldRefusesAnEmptyObjectOrAnArray() throws Exception {
        assertThat(LlmJson.wrapSingleAsField(mapper, mapper.readTree("{}"), "proposals")).isNull();
        assertThat(LlmJson.wrapSingleAsField(mapper, mapper.readTree("[1,2]"), "proposals")).isNull();
    }

    @Test
    void readWholeValueParsesAStandaloneObject() throws Exception {
        JsonNode node = LlmJson.readWholeValue(mapper, "{\"title\":\"a\"}");
        assertThat(node.path("title").asText()).isEqualTo("a");
    }

    @Test
    void readWholeValueStripsAFence() throws Exception {
        JsonNode node = LlmJson.readWholeValue(mapper, "```json\n{\"title\":\"a\"}\n```");
        assertThat(node.path("title").asText()).isEqualTo("a");
    }

    /**
     * The exact shape that made {@code wrapAsField} unsafe on a lone object: two well-formed
     * proposals with one stray closing brace between them, where the FIRST one alone is perfectly
     * valid JSON. A single-shot parse of the WHOLE reply must fail here — {@link LlmJson#readTree}
     * finding that first proposal via its own retry-past-a-bad-brace search is a different, and
     * safe, use of the fragment (salvage explicitly wants it); mistaking it for the whole reply is
     * not, and {@code readWholeValue} exists so nothing does.
     */
    @Test
    void readWholeValueFailsOnAReplyBrokenPartwayThrough() {
        String broken = "{\"proposals\":[{\"kind\":\"ADD\",\"ref\":\"N1\"}},"
            + "{\"kind\":\"ADD\",\"ref\":\"N2\"}]}";
        assertThatThrownBy(() -> LlmJson.readWholeValue(mapper, broken))
            .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void readWholeValueFailsOnTrailingContent() {
        assertThatThrownBy(() -> LlmJson.readWholeValue(mapper, "{\"title\":\"a\"} and then some more"))
            .isInstanceOf(java.io.IOException.class);
    }
}
