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
package com.swarmcoder.app;

import com.swarmcoder.testsupport.LocalCheckouts;
import com.swarmcoder.inference.VllmClient;
import com.swarmcoder.knowledge.Context7Client;
import com.swarmcoder.knowledge.DocsIndex;
import com.swarmcoder.knowledge.Librarian;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Wires the harness's Librarian the same way the product wires the operator's — same
 * constructor, same reference-root list, same {@code lookup_api} and knowledge-brief behaviour
 * ({@link ProjectContext} builds it from {@code project.contextPaths()} at lines ~144-164) — with
 * two differences that make it a HARNESS and not a copy of a real project's setup:
 *
 * <ul>
 *   <li>its index and primer caches live under the caller's own temp directory, never under the
 *       operator's {@code ~/.swarmcoder}, so a test run never pollutes or is polluted by a real
 *       project's cache;</li>
 *   <li>the reference root is REQUIRED. A project the operator configures with no context folder
 *       is a legitimate (if undocumented) choice; a harness meant to reproduce what a documented
 *       project's workers see is not exercising that path when the folder is silently absent — it
 *       is exercising a different, easier product. See run 4's nudge log, 2026-09-03: "steering it
 *       to start (reference material: none)" on every wave-3 worker, because nothing in the
 *       harness had ever built a {@link Librarian} at all and {@code lookup_api} answered from
 *       {@link com.swarmcoder.runtime.ApiLookup#UNAVAILABLE}.</li>
 * </ul>
 */
final class HarnessReferenceRoot {

    /** Overrides the default reference checkout below. */
    static final String REFERENCE_PROPERTY = "swarmcoder.e2e.reference";

    /** The real project the owner points this harness at, same as every other live measurement
     *  in this module ({@code PrefixSizeMeasurementTest}, {@code LiveReferenceDocsTest}). */
    private static final Path DEFAULT_REFERENCE = LocalCheckouts.find("zeroz4j");

    private HarnessReferenceRoot() {
    }

    /**
     * What was configured and what the Librarian found there — folded into link 1's ledger line
     * so a reader sees at a glance that this run's workers had real documentation, not "none".
     */
    record Setup(Librarian librarian, Path root, int documentCount) {

        String describe() {
            return "reference documentation: " + documentCount + " document(s) indexed from "
                + root + " — " + librarian.referenceVersions().strip();
        }
    }

    /**
     * @param projectRoot the harness's own repo checkout — joins the curator's roots as
     *                     {@code "project"}, exactly as {@link ProjectContext} passes
     *                     {@code projectPath}
     * @param cacheDir     where the reference index and primer cache live; must be inside the
     *                     caller's own {@code @TempDir}
     * @throws IllegalStateException when the reference root is not a directory — this harness
     *                                does not run a wave of workers with no documentation and call
     *                                that a passing measurement of the product
     */
    static Setup build(Path projectRoot, Path cacheDir) throws Exception {
        return build(projectRoot, cacheDir, null);
    }

    /**
     * @param primerModel the free local endpoint to distil a one-time framework primer with, or
     *                     null to skip it (the primer is one more section of the brief; every
     *                     other section — the document catalogue, the task-relevant slice, and
     *                     {@code lookup_api} — works fully without it). Null is what this harness
     *                     passes: its cache directory is a fresh {@code @TempDir} every run, so a
     *                     primer built here is never reused, unlike the operator's persistent
     *                     {@code ~/.swarmcoder/primers} — paying a live model call for a distillate
     *                     that is thrown away at the end of the same run buys nothing.
     */
    static Setup build(Path projectRoot, Path cacheDir, VllmClient primerModel) throws Exception {
        Path reference = resolve();
        if (!Files.isDirectory(reference)) {
            throw new IllegalStateException("the ZeroZ checkout at " + reference + " is the "
                + "reference documentation this harness needs; point -D" + REFERENCE_PROPERTY
                + "=<path> at one");
        }
        // Hosted address, no key: Context7Client connects lazily and marks itself unreachable on
        // the first call rather than at construction, so building it here makes no network call
        // and costs nothing when a task's lookup_api answer is already found in the reference
        // folder (which is every query this harness's own tests ask).
        Librarian librarian = new Librarian(
            new Context7Client("https://mcp.context7.com/mcp", null, false),
            new DocsIndex(cacheDir.resolve("docs-index")),
            List.of(reference), projectRoot, primerModel, cacheDir.resolve("primers"));
        return new Setup(librarian, reference, librarian.curator().documentCount());
    }

    private static Path resolve() {
        String declared = System.getProperty(REFERENCE_PROPERTY);
        return declared != null && !declared.isBlank() ? Path.of(declared) : DEFAULT_REFERENCE;
    }
}
