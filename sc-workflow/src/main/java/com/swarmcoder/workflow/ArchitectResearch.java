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
package com.swarmcoder.workflow;

/**
 * Read-only access to the project's reference material for the design roles: the distilled primer
 * for each context folder, plus the ability to go and look something up.
 *
 * <p>This exists so the Architect can design against a framework it has never seen. Pointing a
 * project at, say, a local checkout of a library gives the workers its API through their knowledge
 * brief — but the Architect ran two phases earlier with nothing, so it was inventing an
 * architecture for a framework whose shape it could only guess at.
 *
 * <p>An interface rather than a direct {@code Librarian} dependency so the design roles can be
 * tested without a knowledge stack, and so it is obvious at a glance that these roles can only
 * READ.
 */
public interface ArchitectResearch {

    /** No reference material — the default, and what unscoped/ad-hoc runs get. */
    ArchitectResearch NONE = new ArchitectResearch() {
        @Override
        public String reference(int maxChars) {
            return "";
        }

        @Override
        public String lookupApi(String query) {
            return "no reference material is configured for this project";
        }

        @Override
        public String searchCode(String query) {
            return "no reference material is configured for this project";
        }

        @Override
        public String readFile(String address) {
            return "no reference material is configured for this project";
        }

        @Override
        public String listFolder(String address) {
            return "no reference material is configured for this project";
        }
    };

    /**
     * The standing grounding block: distilled framework primers plus READMEs for every context
     * folder. Deterministic and cached, so it can sit in the prompt prefix without costing a
     * lookup on every call.
     */
    String reference(int maxChars);

    /** Targeted API lookup — local reference folders first, then the docs index. */
    String lookupApi(String query);

    /** Keyword search across the project and its reference folders. */
    String searchCode(String query);

    /** One file's contents, addressed as {@code <root>/<relative path>}. */
    String readFile(String address);

    /** A folder listing, addressed the same way. */
    String listFolder(String address);

    /**
     * The worked examples for a design as a whole — one implementation and one test from the
     * reference material that use what the design's contracts and the project's rules name — for
     * the planner to read before it writes task instructions. "" when there are none, which is
     * also what every research source that predates this answers.
     */
    default String examples(java.util.List<com.swarmcoder.domain.ApiContract> contracts,
                            String goal, int maxChars) {
        return "";
    }
}
