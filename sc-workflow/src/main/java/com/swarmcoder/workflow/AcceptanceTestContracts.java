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

import com.swarmcoder.domain.ApiContract;
import com.swarmcoder.verify.AcceptanceTestLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tells apart a contract that names a real type a task must build from a contract that
 * accidentally names the acceptance test class itself.
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Harness run 19, 2026-09-03. The architect's design listed
 * {@code swarm.accept.PersistenceTest} — the acceptance test class the check
 * {@code retainsDataAfterRestart} is named after — as a contract. {@link TaskGraphValidator}'s
 * "every contract that names a type is delivered by exactly one task" rule then rejected the
 * plan for not assigning that type to a task, but no task ever may: acceptance tests live under
 * the protected directory and are written only by the test author, and the PLAN prompt already
 * forbids planning a task that writes them. The objection sent the planner in a circle — assign
 * the impossible task, or fail and park — three times, every time.
 *
 * <p>The test class is not a contract. It is not a type any task builds; it is the file the test
 * author writes to prove the contracts that ARE built. This class recognises that shape so the
 * rest of the pipeline can treat it as what it is instead of as an unmet promise.
 *
 * <h2>How a contract is recognised as the test class</h2>
 *
 * <p>Two independent signals, either one enough: its type sits in the package the test author
 * actually writes into — derived from {@link AcceptanceTestLocation#WRITE_SUBDIR}, never a
 * literal package name, so this holds for any project's acceptance-test location, not just the
 * demo's {@code swarm.accept}; or its name (fully qualified or simple) is the class named by one
 * of the story's own checks, the {@code ClassName#method} references
 * {@link CriterionEvidence.TestRef} already knows how to read.
 */
public final class AcceptanceTestContracts {

    private AcceptanceTestContracts() {}

    /**
     * The package the test author writes acceptance tests into — {@code swarm.accept} in every
     * project that uses the convention default, whatever module hosts it, because the module
     * prefix varies but this package suffix never does.
     */
    public static String testAuthorPackage() {
        return derivePackage(AcceptanceTestLocation.WRITE_SUBDIR);
    }

    private static String derivePackage(String path) {
        String normalized = path.replace('\\', '/');
        String marker = "java/";
        int at = normalized.indexOf(marker);
        String tail = at >= 0 ? normalized.substring(at + marker.length()) : normalized;
        return tail.replace('/', '.');
    }

    /**
     * True when {@code contract} names the acceptance test class itself rather than a type some
     * task builds.
     *
     * @param checkTestRefs the test references named by the checks in play ({@code
     *                       AcceptanceCriterion#testClassOrFile}, or a planned task's raw
     *                       criterion text) — may be empty or null when none are known yet.
     */
    public static boolean isAcceptanceTestClass(ApiContract contract, Collection<String> checkTestRefs) {
        if (contract == null || !contract.namesAType()) {
            return false;
        }
        String pkg = contract.packageName();
        String testPackage = testAuthorPackage();
        if (pkg.equals(testPackage) || pkg.startsWith(testPackage + ".")) {
            return true;
        }
        if (checkTestRefs == null) {
            return false;
        }
        for (String raw : checkTestRefs) {
            CriterionEvidence.TestRef ref = CriterionEvidence.TestRef.parse(raw);
            if (ref == null || ref.className() == null || ref.className().isBlank()) {
                continue;
            }
            String simple = simpleName(ref.className());
            if (simple.equalsIgnoreCase(contract.simpleTypeName())
                    || ref.className().equalsIgnoreCase(contract.typeName().strip())) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code contracts} with every acceptance-test-class contract removed, reporting each one
     * dropped to {@code onDropped} before it is discarded.
     */
    public static List<ApiContract> withoutAcceptanceTestClasses(List<ApiContract> contracts,
            Collection<String> checkTestRefs, Consumer<ApiContract> onDropped) {
        if (contracts == null || contracts.isEmpty()) {
            return contracts == null ? List.of() : contracts;
        }
        List<ApiContract> kept = new ArrayList<>();
        for (ApiContract contract : contracts) {
            if (isAcceptanceTestClass(contract, checkTestRefs)) {
                if (onDropped != null) {
                    onDropped.accept(contract);
                }
            } else {
                kept.add(contract);
            }
        }
        return kept;
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
