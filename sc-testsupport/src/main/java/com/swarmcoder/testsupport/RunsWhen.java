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
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Says what a test needs from outside the build, and lets it run by itself whenever that is there.
 *
 * <p>It replaces {@code @EnabledIfSystemProperty} and {@code @EnabledIfEnvironmentVariable} on
 * every gated test in this repository, and the change is not cosmetic. Those two annotations are
 * <b>opt-in</b>: the test runs only if somebody remembers a flag, and in this repository nobody
 * ever did — six good browser tests, the only thing that had ever driven the operator interface,
 * went unrun in every build for weeks while reading as covered. This annotation is
 * <b>opt-out</b> for anything the machine can actually do: present tool, test runs.
 *
 * <h2>The polarity is the safety property</h2>
 *
 * <p>There is a trap recorded in this repository: an empty {@code <properties>} block in a pom
 * silently overrides a command-line {@code -D} inside the surefire fork, and it has already made
 * one gate skip with nobody noticing. Under the old scheme a property that failed to reach the
 * fork meant <b>the test silently did not run</b>. Under this one, a property that fails to reach
 * the fork means <b>the test runs</b>. The failure mode of the plumbing is now a test that
 * executes, never a test that vanishes — so the trap cannot cost coverage again, whatever else it
 * breaks.
 *
 * <h2>Skipping is never silent</h2>
 *
 * <p>Whenever this annotation stops a test, {@link NotRun} prints a banner naming the test and the
 * reason, and appends the same line to {@code target/tests-not-run.txt} in the module. A green
 * build therefore always carries the list of what it did not check. See {@link NotRun}.
 *
 * <h2>Turning them off for a tight loop</h2>
 *
 * <p>{@code -Dswarmcoder.skipSlowTests=true}, or {@code SWARMCODER_SKIP_SLOW_TESTS=true} in the
 * environment, skips every test carrying this annotation — announced, like any other skip. It is
 * for somebody iterating on one class who does not want to pay five minutes each time. It is not
 * for the build.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ExtendWith(RunsWhenCondition.class)
public @interface RunsWhen {

    /** Everything the test needs. All of them must be satisfied or the test does not run. */
    Need[] value();

    /** For {@link Need#DOCKER}: the image that must already be built. */
    String image() default "swarmcoder-worker:latest";
}
