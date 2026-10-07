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
package com.swarmcoder.store;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.swarmcoder")
public class StoreArchitectureTest {

    @ArchTest
    public static final ArchRule ONLY_ARTIFACT_STORE_CAN_WRITE_TO_STORE_ROOT =
        noClasses().that().doNotHaveFullyQualifiedName(ArtifactStore.class.getName())
            .and().doNotHaveFullyQualifiedName(StoreRoot.class.getName())
            .should().setFieldWhere(DescribedPredicate.describe("target owner is StoreRoot",
                access -> access.getTarget().getOwner().isAssignableTo(StoreRoot.class)
            ))
            .because("StoreRoot must only be mutated via ArtifactStore to maintain single-writer invariants.");

    /**
     * Every {@code com.swarmcoder.domain} type is reachable from the EclipseStore {@link StoreRoot}
     * (directly or via {@code Lazy<Object>} payloads), and EclipseStore's reflective serializer
     * cannot persist Java records — they must be mutable POJOs (see {@code VerificationReport}).
     * Scoped to the domain package so it never touches the legitimate config/DTO records elsewhere,
     * which are loaded from YAML / packed by BinarySerializer and never persisted in EclipseStore.
     *
     * <p>A type annotated {@link com.swarmcoder.domain.NotReachableFromStoreRoot} is exempt: it is
     * a transient computation result that is built, read, and discarded within one call, and never
     * assigned to a field anywhere in the reachable graph. This is a narrow, per-type escape hatch
     * — each use has to state, in the annotation itself, why the type can never reach the store —
     * not a way to weaken the rule generally. {@code StoreRootReachabilityTest} is the empirical
     * backstop: if an exempted type is ever actually wired into a field reachable from
     * {@link StoreRoot}, that test fails independently of this annotation.
     */
    private static final ArchCondition<JavaClass> NOT_BE_A_RECORD =
        new ArchCondition<>("not be a Java record") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (item.isRecord() && !item.isAnnotatedWith(
                        com.swarmcoder.domain.NotReachableFromStoreRoot.class)) {
                    events.add(SimpleConditionEvent.violated(item, item.getFullName()
                        + " is a Java record; EclipseStore-persisted domain types must be mutable "
                        + "POJOs (no-arg + all-args ctor, getters/setters) — see VerificationReport"));
                }
            }
        };

    @ArchTest
    public static final ArchRule PERSISTED_DOMAIN_TYPES_ARE_NOT_RECORDS =
        classes().that().resideInAPackage("com.swarmcoder.domain..")
            .should(NOT_BE_A_RECORD)
            .because("EclipseStore cannot persist Java records; persisted domain types must be POJOs");

}
