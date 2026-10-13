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

import java.util.Set;

/**
 * The lookups that are one fact for a role and for a worker: the same computation for both,
 * differing only in how a file's address is written (section 101).
 *
 * <p>Each is computed once with its addresses marked ({@link Addresses}) and kept in that form
 * by {@link SharedAnswers#answerFor}. Which lookups are here is decided by what they compute,
 * and each was held against the reader's own computation, character for character, in
 * {@code ARoleAndAWorkerShareOneComputationTest}:
 * <ul>
 *   <li>{@code types_in}, {@code build_of}, {@code resources_of}, {@code body_of}: the tree
 *       queries, which read their argument the same way for every reader;</li>
 *   <li>{@code doc_outline} of one document or of all of them, {@code doc_section},
 *       {@code doc_search}: a reader names a document in its own way, so the document is found
 *       for the reader first and the question is then about that document;</li>
 *   <li>{@code supertypes_of}, {@code callers_of}, {@code doc_of}: the language server's own.</li>
 * </ul>
 * Not here, because the two readers are given different texts or different questions are put:
 * a type's shape ({@code public_shape} names what was looked up and falls back to the code
 * that uses the type; a worker's {@code shape_of} is the bare declaration), where something is
 * used and what implements it (a role is answered from the tree for a symbol the project does
 * not declare, with a heading of its own), {@code find_symbol} (a role is also told what the
 * tree knows of the name), and a folder's documents (a path without a root means a different
 * folder to each). For the first three the language server's part is still asked once for
 * both: see {@code LanguageQueries}.
 */
final class ForEveryReader {

    private static final Set<String> BY_THE_SERVER = Set.of("supertypes_of", "callers_of",
        "doc_of");

    private ForEveryReader() {
    }

    /**
     * The question as it is put for every reader, or null when this lookup with this argument
     * is the reader's own.
     */
    static String questionOf(KnowledgeCurator curator, Addresses reader, String query,
                             String argument) {
        if (query == null || argument == null) {
            return null;
        }
        switch (query) {
            case "types_in", "build_of", "resources_of", "body_of", "doc_search",
                 "supertypes_of", "callers_of", "doc_of":
                return argument;
            case "doc_outline": {
                if (argument.isBlank()) {
                    return argument;
                }
                // Null for a folder or a name that is no document: listed for the reader.
                return DocumentQueries.of(curator, reader).addressOf(argument.strip());
            }
            case "doc_section": {
                String asked = argument.strip();
                int hash = asked.indexOf('#');
                String document = DocumentQueries.of(curator, reader)
                    .addressOf(hash < 0 ? asked : asked.substring(0, hash));
                return document == null ? null
                    : document + (hash < 0 ? "" : asked.substring(hash));
            }
            default:
                return null;
        }
    }

    /** The answer with every address marked; {@code asks} is what {@link #questionOf} gave. */
    static String answer(KnowledgeCurator curator, String query, String asks) {
        return switch (query) {
            case "types_in" -> TreeQueries.forEveryReaderOf(curator).typesIn(asks);
            case "build_of" -> TreeQueries.forEveryReaderOf(curator).buildOf(asks);
            case "resources_of" -> TreeQueries.forEveryReaderOf(curator).resourcesOf(asks);
            case "body_of" -> TreeQueries.forEveryReaderOf(curator).bodyOf(asks);
            case "doc_outline" -> DocumentQueries.forEveryReaderOf(curator).outlineOf(asks);
            case "doc_section" -> DocumentQueries.forEveryReaderOf(curator).sectionOf(asks);
            case "doc_search" -> DocumentQueries.forEveryReaderOf(curator).search(asks);
            case "supertypes_of" -> LanguageQueries.forEveryReaderOf(curator).supertypesOf(asks);
            case "callers_of" -> LanguageQueries.forEveryReaderOf(curator).callersOf(asks);
            case "doc_of" -> LanguageQueries.forEveryReaderOf(curator).docOf(asks);
            default -> throw new Addresses.NotForEveryone("no such lookup for every reader");
        };
    }

    /** What a reader's own computation would have said of where the answer came from. */
    static String tagFor(Addresses reader, String query) {
        return reader.ofAWorker() && BY_THE_SERVER.contains(query) ? "server" : null;
    }
}
