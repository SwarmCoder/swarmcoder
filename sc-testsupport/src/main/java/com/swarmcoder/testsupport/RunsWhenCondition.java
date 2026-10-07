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

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.util.AnnotationUtils;

import java.util.Optional;

/**
 * Decides whether a {@link RunsWhen} test runs, and announces it through {@link NotRun} when it
 * does not.
 *
 * <p>A method's own {@code @RunsWhen} wins over its class's, so a class of tests needing Chromium
 * can hold one method that also needs a live model.
 */
public class RunsWhenCondition implements ExecutionCondition {

    private static final ConditionEvaluationResult RUN =
        ConditionEvaluationResult.enabled("everything it needs is here");

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<RunsWhen> declared = AnnotationUtils.findAnnotation(
            context.getElement(), RunsWhen.class);
        if (declared.isEmpty()) {
            return RUN;
        }
        RunsWhen runsWhen = declared.get();
        String test = name(context);

        if (skipRequested()) {
            return stop(test, "every test that needs an outside tool was switched off by hand "
                + "with swarmcoder.skipSlowTests. Nothing about the interface, the container "
                + "hardening or the real test runner was checked in this build.");
        }

        for (Need need : runsWhen.value()) {
            Optional<String> missing = reasonMissing(need, runsWhen.image());
            if (missing.isPresent()) {
                return stop(test, missing.get());
            }
        }
        return RUN;
    }

    private static Optional<String> reasonMissing(Need need, String image) {
        return switch (need) {
            case CHROMIUM -> Tools.chromiumMissing();
            case DOCKER -> Tools.dockerMissing(image);
            case MAVEN -> Tools.mavenMissing();
            case LIVE_MODEL -> Tools.liveModelMissing();
            case PAID_CLOUD_MODELS -> Tools.paidCloudModelsMissing();
            case DEMO_REPO -> Tools.demoRepoMissing();
        };
    }

    private static ConditionEvaluationResult stop(String test, String reason) {
        NotRun.announce(test, reason);
        return ConditionEvaluationResult.disabled(reason);
    }

    /** The tight-loop escape hatch. Announced like any other skip, never silent. */
    private static boolean skipRequested() {
        return "true".equalsIgnoreCase(System.getProperty("swarmcoder.skipSlowTests"))
            || "true".equalsIgnoreCase(System.getenv("SWARMCODER_SKIP_SLOW_TESTS"));
    }

    private static String name(ExtensionContext context) {
        return context.getTestClass().map(Class::getSimpleName).orElse("?")
            + context.getTestMethod().map(m -> "#" + m.getName()).orElse("");
    }
}
