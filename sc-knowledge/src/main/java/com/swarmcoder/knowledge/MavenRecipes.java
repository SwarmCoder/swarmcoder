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

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.RecipeRun;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.maven.AddDependency;
import org.openrewrite.maven.MavenParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * How the system itself declares a dependency in a pom: by recipe, not by string surgery.
 *
 * <p><b>Why this exists.</b> When the pre-flight finds that a stated rule names an artifact no
 * module declares, it now adds that declaration to the plan
 * ({@code BuildFilesInTheJob.declareMissing}). Somebody has to write those three lines. A worker
 * writing them is fine — it has a compiler behind it and it will find out. The SYSTEM writing them
 * by hand is not: a pom is XML with a schema, a formatting convention, comments a person put there
 * on purpose, and a {@code <dependencyManagement>} section that means the opposite of the
 * {@code <dependencies>} section three tags away. Inserting text before the first
 * {@code </dependencies>} is right until the file has two of them.
 *
 * <p>{@code org.openrewrite.maven.AddDependency} is a refactoring recipe over the same tree the
 * {@link SemanticIndex} reads. It puts the block in the module's own dependency list, in the
 * position the file's existing order implies, at the file's own indentation, and it leaves every
 * other byte alone — which is a property this codebase asserts rather than assumes
 * ({@code TheRecipeAddsTheBlockAndChangesNothingElseTest}).
 *
 * <p><b>What it does not do.</b> It does not decide WHETHER a dependency should be added, or which
 * module gets it — that is the pre-flight's judgement and is stated with its reasons. And it is
 * never imposed on a worker: a worker told to declare a dependency edits the pom however it likes,
 * because it has a build to answer to.
 *
 * <p><b>The Maven parser, not the XML parser.</b> {@code AddDependency} reads a
 * {@code MavenResolutionResult} marker off the document and does nothing at all without it; only
 * {@code MavenParser} attaches one. That parser resolves the pom's parent chain, so this is for
 * the orchestrator process, never for a sandboxed worker.
 */
public final class MavenRecipes {

    private static final Logger log = LoggerFactory.getLogger(MavenRecipes.class);

    private MavenRecipes() {}

    /**
     * The pom, with one dependency added.
     *
     * @param version null or blank when the version is managed by a parent or a BOM, which is the
     *                normal case here and the one the pre-flight's instruction insists on. The
     *                recipe is given the empty string, which it writes as no {@code <version>}
     *                element at all.
     * @param scope   null for the default compile scope
     * @return the new text of the pom, or "" when nothing changed or nothing could be parsed —
     *         never an exception, because this is an improvement on a text edit and not a
     *         precondition for anything
     */
    public static String withDependency(String pomText, String groupId, String artifactId,
                                        String version, String scope) {
        if (pomText == null || pomText.isBlank() || groupId == null || groupId.isBlank()
            || artifactId == null || artifactId.isBlank()) {
            return "";
        }
        try {
            ExecutionContext ctx = new InMemoryExecutionContext(
                t -> log.debug("openrewrite maven: {}", t.toString()));
            List<SourceFile> poms = MavenParser.builder().build().parse(ctx, pomText).toList();
            if (poms.isEmpty()) {
                return "";
            }
            Recipe recipe = new AddDependency(groupId.strip(), artifactId.strip(),
                version == null ? "" : version.strip(),
                null, scope == null || scope.isBlank() ? null : scope.strip(),
                null, null, null, null, null, null, null);
            RecipeRun run = recipe.run(new InMemoryLargeSourceSet(poms), ctx);
            List<Result> results = run.getChangeset().getAllResults();
            for (Result result : results) {
                if (result.getAfter() != null) {
                    return result.getAfter().printAll();
                }
            }
            return "";
        } catch (Throwable e) {                                            // noqa
            log.warn("could not add {}:{} to a pom by recipe ({}); the caller writes it by hand",
                groupId, artifactId, e.toString());
            return "";
        }
    }

    /** The same, over a file on disk. Returns false when nothing was written. */
    public static boolean declareIn(Path pom, String groupId, String artifactId, String version,
                                    String scope) {
        if (pom == null || !Files.isRegularFile(pom)) {
            return false;
        }
        try {
            String before = Files.readString(pom, StandardCharsets.UTF_8);
            String after = withDependency(before, groupId, artifactId, version, scope);
            if (after.isEmpty() || after.equals(before)) {
                return false;
            }
            Files.writeString(pom, after, StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {                                            // noqa
            log.warn("could not write {} : {}", pom, e.getMessage());
            return false;
        }
    }

    /**
     * The sentence that tells a worker — or a person reading the plan — that this codebase has a
     * mechanical way to do it, without taking the choice away.
     */
    public static String howToDeclare(String groupId, String artifactId, boolean versionManaged) {
        return "Add it to the module's `pom.xml`" + (versionManaged
            ? " with no `<version>` element — the parent or a BOM manages it." : ".")
            + " If you would rather not hand-edit the XML, this project can apply OpenRewrite's "
            + "`org.openrewrite.maven.AddDependency` recipe for `" + groupId + ":" + artifactId
            + "`, which inserts the block in the right dependency list and leaves the rest of the "
            + "file byte-identical. Either way is fine; the build is what decides.";
    }
}
