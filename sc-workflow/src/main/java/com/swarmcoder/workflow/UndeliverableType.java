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

import com.swarmcoder.domain.Task;
import com.swarmcoder.verify.AcceptanceTestLocation;
import com.swarmcoder.verify.TypeDeliverability;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A wave's tests do not compile because a type is missing. Which of the two things is that?
 *
 * <ul>
 *   <li><b>The healthy one.</b> The type is what this wave is about to write. A test written
 *       before its code cannot compile, and that is the whole idea.</li>
 *   <li><b>The one that costs a run.</b> The type is not a contract anybody delivers and not
 *       inside the write set of any task that has yet to run. Nobody will ever create it, so this
 *       wave's swarm will fail, its repair wave will fail identically, and every candidate will be
 *       told it broke a tree it never touched.</li>
 * </ul>
 *
 * <p>The two look the same from every gate downstream — "does not compile" — which is why run 13
 * spent three waves reaching one and then blamed the workers. Here they are separable, because at
 * wave time the plan is known: who delivers what, and who is still allowed to write where.
 *
 * <p><b>It fails open, everywhere.</b> No missing types parsed, an unreadable write set, a task
 * that owns a whole source root — all of those mean the question could not be settled, and an
 * unsettled question dispatches the wave exactly as before. It stops a run only when it can say,
 * concretely, that a named type is in nobody's job.
 */
public final class UndeliverableType {

    private UndeliverableType() {}

    /**
     * The reason this wave must park, or null to dispatch it.
     *
     * @param task      the task whose tests would not compile
     * @param remaining every task that has not run yet — this wave and the waves after it. A task
     *                  in an EARLIER wave cannot help: it already ran, and what it delivered is
     *                  in the tree that just failed to compile.
     * @param missing   the fully-qualified types the compiler said do not exist
     */
    public static String park(Task task, List<Task> remaining, List<String> missing) {
        return park(task, remaining, missing, null, null);
    }

    /**
     * As {@link #park(Task, List, List)}, telling library packages apart (section 109): a name
     * that is a package of a library is not a type a task delivers. When the build file of the
     * module the task's tests are in may still be written by this task or one still to run, the
     * library can still be declared there and the name does not stop the run; otherwise the
     * message says which build file declares the library and which does not.
     *
     * @param tree            the tree the tests did not compile on; null when not known
     * @param localRepository the local Maven repository the jars are read from; null for none
     */
    static String park(Task task, List<Task> remaining, List<String> missing, Path tree,
                       Path localRepository) {
        if (task == null || missing == null || missing.isEmpty()) {
            return null;
        }
        List<Task> stillToRun = remaining == null ? List.of() : remaining;
        // The same classifier RedChecker uses at TEST_AUTHORING (com.swarmcoder.verify.
        // TypeDeliverability) - one implementation of "who may still create this", asked here at
        // wave time and there before a single wave has run.
        List<String> undeliverable = TypeDeliverability.undeliverable(missing, stillToRun);
        if (undeliverable.isEmpty()) {
            return null;
        }
        Map<String, LibraryPackages.Found> libraries =
            LibraryPackages.among(tree, undeliverable, localRepository);
        if (!libraries.isEmpty()) {
            String module = AcceptanceTestLocation.moduleOf(task.acceptanceTestDir());
            String buildFile = module.isEmpty() ? "pom.xml" : module + "/pom.xml";
            boolean declarable = mayWrite(task, buildFile);
            for (Task later : stillToRun) {
                declarable |= mayWrite(later, buildFile);
            }
            List<String> projectNames = undeliverable.stream()
                .filter(name -> !libraries.containsKey(name)).toList();
            if (declarable && projectNames.isEmpty()) {
                return null; // a task still to run may declare the library in that build file
            }
            return libraryMessage(task, projectNames, libraries, module, buildFile, declarable);
        }
        StringBuilder sb = new StringBuilder("Task '").append(task.title()).append("': its tests need ");
        for (int i = 0; i < undeliverable.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append('`').append(undeliverable.get(i)).append('`');
        }
        sb.append(", which no task delivers and '")
            .append(task.title()).append("' cannot create — the plan or the tests are wrong.\n\n")
            .append("This task may only write ").append(sorted(task.writeSet()))
            .append(", and no task that has yet to run promises ")
            .append(undeliverable.size() == 1 ? "that type" : "those types")
            .append(". The waves in front of this one have already been built and integrated, so "
                + "nothing further along will create ")
            .append(undeliverable.size() == 1 ? "it" : "them")
            .append(" either. Dispatching a swarm now would fail every candidate for a tree that "
                + "none of them broke, and the repair wave after it would fail in exactly the same "
                + "way.\n\nDecide which side is wrong: add the missing type to the design as a "
                + "contract, so a task is made to deliver it, or correct the acceptance tests to "
                + "use the types this plan actually builds. Then resume the run.");
        return sb.toString();
    }

    /**
     * The message when some of the missing names are library packages: which library, which
     * build file declares it, and that the build file of the test's own module does not.
     */
    private static String libraryMessage(Task task, List<String> projectNames,
                                         Map<String, LibraryPackages.Found> libraries,
                                         String module, String buildFile, boolean declarable) {
        String where = module.isEmpty() ? "the repository root" : module;
        StringBuilder sb = new StringBuilder("Task '").append(task.title())
            .append("': its acceptance tests, in ").append(where).append(", do not compile.");
        Map<String, List<String>> byArtifact = new LinkedHashMap<>();
        for (LibraryPackages.Found found : libraries.values()) {
            byArtifact.computeIfAbsent(found.artifact(), a -> new ArrayList<>()).add(found.name());
        }
        boolean placement = false;
        for (Map.Entry<String, List<String>> library : byArtifact.entrySet()) {
            List<String> names = library.getValue();
            List<String> declaredBy = libraries.get(names.get(0)).declaredBy();
            sb.append("\n\n").append(quoted(names))
                .append(names.size() == 1 ? " is a package" : " are packages")
                .append(" of the library ").append(library.getKey())
                .append(", not a type a task delivers. ");
            if (declaredBy.isEmpty()) {
                sb.append("It is declared by no build file this task or an earlier one writes.");
            } else {
                placement = true;
                sb.append("It is declared by ").append(String.join(", ", declaredBy))
                    .append(", and not by ").append(buildFile)
                    .append(", the build file of the module the tests are in.");
            }
        }
        if (!projectNames.isEmpty()) {
            sb.append("\n\n").append(quoted(projectNames))
                .append(projectNames.size() == 1 ? " is" : " are")
                .append(" delivered by no task, and '").append(task.title())
                .append("' cannot create ").append(projectNames.size() == 1 ? "it" : "them")
                .append(". This task may only write ").append(sorted(task.writeSet()))
                .append('.');
        }
        sb.append("\n\nDispatching a swarm now would fail every candidate for a tree that none "
            + "of them broke.");
        if (!declarable) {
            sb.append(" No task that has yet to run may write ").append(buildFile).append('.');
        }
        sb.append("\n\nDecide which side is wrong: ");
        if (placement) {
            sb.append("the tests are in the wrong module (they belong in the module whose build "
                + "file declares the library), or ").append(buildFile)
                .append(" must declare it.");
        } else {
            sb.append(buildFile).append(" must declare the library, or the tests must not "
                + "use it.");
        }
        if (!projectNames.isEmpty()) {
            sb.append(" For the other names: add them to the design as contracts, or correct "
                + "the tests to use the types this plan builds.");
        }
        return sb.append(" Then resume the run.").toString();
    }

    private static String quoted(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            sb.append(sb.isEmpty() ? "" : ", ").append('`').append(name).append('`');
        }
        return sb.toString();
    }

    /** Whether a task's write set holds this path: the path itself or a directory above it. */
    private static boolean mayWrite(Task task, String path) {
        if (task == null || task.writeSet() == null) {
            return false;
        }
        for (String raw : task.writeSet()) {
            String entry = raw == null ? "" : raw.strip().replace('\\', '/')
                .replaceAll("^(\\./)+", "").replaceAll("/+$", "");
            if (!entry.isEmpty() && (entry.equals(path) || path.startsWith(entry + "/"))) {
                return true;
            }
        }
        return false;
    }

    private static String sorted(java.util.Set<String> writeSet) {
        if (writeSet == null || writeSet.isEmpty()) {
            return "nothing at all";
        }
        List<String> paths = new ArrayList<>(writeSet);
        java.util.Collections.sort(paths);
        return String.join(", ", paths);
    }
}
