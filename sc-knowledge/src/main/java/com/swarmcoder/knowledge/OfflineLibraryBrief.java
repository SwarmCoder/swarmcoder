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

import com.swarmcoder.domain.LibraryDoc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The second half of a worker's "Available libraries" heading: what this build does NOT declare
 * yet and the worker may declare itself.
 *
 * <p><b>Why a worker needs this.</b> Until now the brief listed what the build declares and
 * nothing else, and a worker's write set stopped at {@code src/main/java}, so "the rules say use
 * EclipseStore and the pom does not have it" was a dead end — four workers of run
 * {@code ede2068b} reached it independently and one of them concluded, correctly for the world it
 * was in, "the poms are locked, so the server must use an in-memory root". Workers can now edit a
 * module's build file. The moment they can, the question changes from "what may I use?" to "what
 * may I ADD?", and that question has a precise, cheap answer: an artifact whose version is already
 * pinned by an imported BOM or a parent pom, and whose files are already in the offline Maven
 * repository the sandbox mounts. Anything else fails to resolve in a container with no network.
 *
 * <p><b>Kept short deliberately.</b> This sits in the shared prompt prefix, which is charged
 * against every worker's working context and is the floor history trimming can never compact away.
 * So the list is filtered to the groups the project already builds with — plus anything the
 * project's own rules name by hand, which is the case that matters most — and capped. A worker
 * that wants something outside the list can still ask; the list exists to make the common case
 * free, not to be a catalogue.
 */
public final class OfflineLibraryBrief {

    /**
     * The most artifacts ever listed. A framework BOM manages a dozen; a platform BOM (Spring,
     * Quarkus) manages a thousand, and printing those would cost more prefix than the rest of the
     * brief put together.
     */
    public static final int MAX_LISTED = 25;

    private final DeclarableArtifacts.Catalog catalog;
    private final List<LibraryDoc> declaredAcrossModules;
    private final String projectRules;

    /**
     * @param catalog               every artifact managed AND present offline
     * @param declaredAcrossModules what every module of the build already declares directly, so
     *                              nothing is offered that is already there
     * @param projectRules          the project's stated rules; an artifact one of them names is
     *                              listed even when its group is not one the build already uses,
     *                              because that is exactly the artifact the work will need
     */
    public OfflineLibraryBrief(DeclarableArtifacts.Catalog catalog,
                               List<LibraryDoc> declaredAcrossModules, String projectRules) {
        this.catalog = catalog;
        this.declaredAcrossModules = declaredAcrossModules == null ? List.of()
            : List.copyOf(declaredAcrossModules);
        this.projectRules = projectRules == null ? "" : projectRules;
    }

    /** The artifacts a worker may declare, filtered and capped — empty when there are none. */
    public List<DeclarableArtifacts.Artifact> offered() {
        if (catalog == null || catalog.isEmpty()) {
            return List.of();
        }
        Set<String> declaredIds = new LinkedHashSet<>();
        List<String> coordinates = new ArrayList<>();
        for (LibraryDoc library : declaredAcrossModules) {
            String coordinate = library.coordinate() == null ? "" : library.coordinate();
            coordinates.add(coordinate);
            int colon = coordinate.indexOf(':');
            if (colon >= 0 && colon + 1 < coordinate.length()) {
                declaredIds.add(coordinate.substring(colon + 1));
            }
        }
        Set<String> groups = DeclarableArtifacts.groupsOf(coordinates);
        Set<String> named = RulesVersusManifest.artifactsNamedInRules(projectRules);

        List<DeclarableArtifacts.Artifact> offered = new ArrayList<>();
        for (DeclarableArtifacts.Artifact artifact : catalog.artifacts()) {
            if (declaredIds.contains(artifact.artifactId())) {
                continue;   // already in the build; the first list has it
            }
            if (!groups.contains(artifact.groupId()) && !named.contains(artifact.artifactId())) {
                continue;
            }
            offered.add(artifact);
            if (offered.size() >= MAX_LISTED) {
                break;
            }
        }
        return offered;
    }

    /**
     * The markdown block, or "" when there is nothing to offer.
     *
     * <p>No versions are printed, and that is the point: the version is decided by the BOM, so the
     * declaration a worker writes has no {@code <version>} element in it and there is nothing for a
     * model to get wrong. The sentence says so, in the one line where it can be acted on.
     */
    public String render() {
        List<DeclarableArtifacts.Artifact> offered = offered();
        if (offered.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
            "\n### Libraries you may ADD to a module's pom.xml if your task needs them\n"
            + "Each one's version is already fixed by this build's BOM or parent pom, so declare "
            + "groupId and artifactId with NO <version> element. These are in the offline "
            + "repository; anything not listed here or above cannot be resolved (the build has no "
            + "network) and will fail.\n");
        for (DeclarableArtifacts.Artifact artifact : offered) {
            sb.append("- ").append(artifact.coordinate()).append('\n');
        }
        return sb.toString();
    }
}
