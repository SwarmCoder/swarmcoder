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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/**
 * Defensive JSON parsing for LLM output. Local models wrap JSON in prose or code fences,
 * emit several objects back to back, or produce malformed leads like a doubled opening
 * brace ({@code {{"score": …}} observed live from Qwen 3.6 even under json_object mode).
 * Parsing tries a balanced extraction from each successive '{' until one deserializes.
 *
 * <p>Two more shapes are tolerated, both seen from a paid endpoint (2026-09-03): a reply
 * fenced in {@code ```json … ```}, and a reply that is a JSON <em>string</em> whose content
 * is itself the JSON object — {@code {\"files\": [...]}} with the outer quotes missing, or
 * {@code "{\"files\": [...]}"} with them present — which is what a model produces when it
 * double-encodes its own answer. Both parse as garbage until unwrapped once. Unknown fields
 * are ignored rather than rejected, since a harmless extra field from the model must not cost
 * the whole reply the way a strict mapper does.
 */
public final class LlmJson {

    private static final int MAX_ATTEMPTS = 5;

    private LlmJson() {}

    public static <T> T parse(ObjectMapper mapper, String raw, Class<T> type) throws IOException {
        return parse(mapper, raw, json -> readLenient(mapper, json, type));
    }

    /**
     * The same tolerant extraction as {@link #parse(ObjectMapper, String, Class)}, but returns the
     * raw tree instead of mapping onto a class — for a caller that reads fields itself (an array of
     * mixed shapes, a field the schema does not name yet) rather than binding onto a POJO.
     */
    public static JsonNode readTree(ObjectMapper mapper, String raw) throws IOException {
        return parse(mapper, raw, mapper::readTree);
    }

    /**
     * Normalises the shapes a model sends instead of the {@code {"<canonicalField>": [...]}}
     * envelope it was asked for, so a caller that only ever reads one named field never has to
     * special-case the alternatives itself. Tolerates, in order: the field already present under
     * {@code canonicalField} or one of {@code aliasFields} (a model that called the array
     * "items" instead of the name it was given); and the reply being the bare array itself, with
     * no wrapping object at all.
     *
     * <p><b>Deliberately does NOT wrap a lone object as a one-element array.</b> {@code root} may
     * be a fragment {@link #readTree}'s own retry-past-a-bad-brace found buried inside a LARGER,
     * still-broken reply — a real proposal object in its own right, just not the whole reply — and
     * a caller gating a one-retry contract on "did this fail to parse" must not count that as
     * success. Wrapping one object that IS genuinely the whole reply is {@link #wrapSingleAsField},
     * gated on {@link #readWholeValue} rather than on this method's caller-supplied {@code root}.
     *
     * @return a new object node with the array under {@code canonicalField}, or {@code null} when
     *         {@code root} matches none of the above and nothing usable could be found
     */
    public static JsonNode wrapAsField(ObjectMapper mapper, JsonNode root, String canonicalField,
                                        String... aliasFields) {
        if (root.has(canonicalField) && root.path(canonicalField).isArray()) {
            return root;
        }
        for (String alias : aliasFields) {
            JsonNode candidate = root.path(alias);
            if (candidate.isArray()) {
                ObjectNode wrapped = mapper.createObjectNode();
                wrapped.set(canonicalField, candidate);
                return wrapped;
            }
        }
        if (root.isArray()) {
            ObjectNode wrapped = mapper.createObjectNode();
            wrapped.set(canonicalField, root);
            return wrapped;
        }
        return null;
    }

    /**
     * Wraps a single, non-empty object as a one-element array under {@code canonicalField} — for a
     * model that dropped the array envelope and sent one entry as if that were the whole reply.
     * Returns {@code null} for anything else (empty object, array, scalar).
     *
     * <p>Callers gating a retry on parse failure must pass a {@code root} obtained from
     * {@link #readWholeValue}, never from {@link #readTree} — see {@link #wrapAsField}'s note on
     * why a lone object cannot safely be trusted to be the whole reply otherwise.
     */
    public static JsonNode wrapSingleAsField(ObjectMapper mapper, JsonNode root, String canonicalField) {
        if (!root.isObject() || root.isEmpty()) {
            return null;
        }
        ObjectNode wrapped = mapper.createObjectNode();
        wrapped.putArray(canonicalField).add(root);
        return wrapped;
    }

    /**
     * A raw reply, parsed as exactly ONE JSON value with nothing left over but whitespace — never
     * a fragment. Strips a wrapping code fence first (the one {@link #readTree} tolerance that
     * costs nothing here, since a fence is unambiguous and never itself part of the JSON), then
     * requires the WHOLE remaining text to parse as a single value.
     *
     * <p>Unlike {@link #readTree}, this never hunts for a well-formed value buried inside a
     * larger, broken one: on a syntax error it throws immediately, and on trailing content after an
     * otherwise-valid value it throws too. That is the whole point of it — a caller that must tell
     * "the reply IS this object" from "this object happens to be a well-formed piece of a reply
     * that, as a whole, is not" needs a reader that a fragment cannot fool.
     */
    public static JsonNode readWholeValue(ObjectMapper mapper, String raw) throws IOException {
        String body = stripCodeFence(raw).strip();
        try (com.fasterxml.jackson.core.JsonParser parser = mapper.getFactory().createParser(body)) {
            JsonNode node = mapper.readTree(parser);
            if (node == null) {
                throw new IOException("empty reply");
            }
            if (parser.nextToken() != null) {
                throw new IOException("trailing content after the JSON value");
            }
            return node;
        }
    }

    /** How a candidate substring is turned into the caller's shape — a POJO class or a raw tree. */
    @FunctionalInterface
    private interface Reader<T> {
        T read(String json) throws IOException;
    }

    private static <T> T parse(ObjectMapper mapper, String raw, Reader<T> reader) throws IOException {
        return parse(mapper, raw, reader, true);
    }

    private static <T> T parse(ObjectMapper mapper, String raw, Reader<T> reader, boolean allowUnwrap)
            throws IOException {
        String body = stripCodeFence(raw).strip();

        // Whole-body case: the reply is a JSON string literal and the object is its CONTENT
        // ({@code "{\"files\": [...]}"}) rather than the object itself. Unwrap before hunting
        // for a brace, or the search lands on the escaped inner '{' and the balanced-extraction
        // below — which tracks string state to find the matching '}' — reads the backslash
        // before every escaped quote as ordinary text (it is one, outside a real string) and
        // never leaves "in string" mode, so it runs off the end of the reply instead of closing.
        if (allowUnwrap && body.length() > 1 && body.charAt(0) == '"'
                && body.charAt(body.length() - 1) == '"') {
            String unescaped = unescapeJsonStringLiteral(mapper, body);
            if (unescaped != null) {
                try {
                    return parse(mapper, unescaped, reader, false);
                } catch (IOException ignored) {
                    // Not actually a wrapped object — fall through to normal extraction.
                }
            }
        }

        IOException lastFailure = null;
        int from = 0;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int start = body.indexOf('{', from);
            if (start < 0) {
                break;
            }
            String candidate = extractFirstObject(body.substring(start));
            try {
                return reader.read(candidate);
            } catch (IOException e) {
                lastFailure = e;
                if (allowUnwrap) {
                    // Same-body case: no outer quotes at all, e.g. {\"files\": [...]} — every
                    // quote in the reply is backslash-escaped as if the whole thing sat inside a
                    // JSON string, but the string never happened; only the escaping did.
                    String unescaped = unescapeJsonStringLiteral(mapper, "\"" + candidate + "\"");
                    if (unescaped != null && !unescaped.equals(candidate)) {
                        try {
                            return parse(mapper, unescaped, reader, false);
                        } catch (IOException e2) {
                            lastFailure = e2;
                        }
                    }
                }
                from = start + 1;
            }
        }
        throw lastFailure != null ? lastFailure
            : new IOException("no JSON object in LLM response: " + truncate(body));
    }

    /** First balanced object (string-aware) — never first-brace-to-last-brace. */
    public static String extractFirstObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return text;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (inString) {
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return text.substring(start);
    }

    /** {@code mapper.readValue}, but a field the schema does not know about is not an error. */
    private static <T> T readLenient(ObjectMapper mapper, String json, Class<T> type) throws IOException {
        return mapper.readerFor(type)
            .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .readValue(json);
    }

    /**
     * Parses {@code quoted} as a JSON string literal and returns its unescaped content, or
     * {@code null} when it is not one — never the mapper's exception, since every caller here
     * treats "not a wrapped string" as just another failed attempt, not a reason to give up.
     */
    private static String unescapeJsonStringLiteral(ObjectMapper mapper, String quoted) {
        try {
            return mapper.readerFor(String.class).readValue(quoted);
        } catch (IOException e) {
            return null;
        }
    }

    /** Strips a single leading/trailing ``` fence (with an optional language tag), if present. */
    private static String stripCodeFence(String text) {
        String stripped = text.strip();
        if (!stripped.startsWith("```")) {
            return text;
        }
        int firstNewline = stripped.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        String withoutOpen = stripped.substring(firstNewline + 1);
        int close = withoutOpen.lastIndexOf("```");
        return close < 0 ? withoutOpen : withoutOpen.substring(0, close);
    }

    private static String truncate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
