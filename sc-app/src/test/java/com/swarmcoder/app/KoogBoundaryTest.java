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

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Rule R4(d) / spec §2: Koog types are confined to sc-runtime. sc-app sees every module,
 * so this test covers the whole codebase.
 */
@AnalyzeClasses(packages = "com.swarmcoder")
public class KoogBoundaryTest {

    @ArchTest
    public static final ArchRule KOOG_CONFINED_TO_SC_RUNTIME =
        noClasses().that().resideInAPackage("com.swarmcoder..")
            .and().resideOutsideOfPackage("com.swarmcoder.runtime..")
            .should().dependOnClassesThat().resideInAPackage("ai.koog..")
            .because("Koog is consumed strictly behind the AgentRuntime facade in sc-runtime "
                + "(DEVELOPER_CORRECTIONS.md R4; spec §2)");
}
