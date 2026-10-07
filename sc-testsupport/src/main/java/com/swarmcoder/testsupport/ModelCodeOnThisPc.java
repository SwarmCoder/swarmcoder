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
package com.swarmcoder.testsupport;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lets the engines this test class builds run their commands on this PC, with no container.
 *
 * <p>An engine with no Docker sandbox refuses to run a model's commands or the builds and tests
 * that execute model-written code (see {@code com.swarmcoder.domain.HostExecution}). A scripted
 * unit test has no model: what its "worker" runs is the test's own fixture, in a temporary folder.
 * Such a test opts in here, by name, and the permission ends when the class does.
 *
 * <p>Never on a test that talks to a real model. Those use the container
 * ({@code HarnessSandbox.required()}).
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(ModelCodeOnThisPcExtension.class)
public @interface ModelCodeOnThisPc {

    /** Why this is safe: what actually runs, in a few words. */
    String value() default "scripted model: the only commands run are this test's own fixture";
}
