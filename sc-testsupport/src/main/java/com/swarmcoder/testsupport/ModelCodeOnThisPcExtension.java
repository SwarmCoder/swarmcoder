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

import com.swarmcoder.domain.HostExecution;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Grants {@link HostExecution} for the life of a test class carrying {@link ModelCodeOnThisPc}. */
public final class ModelCodeOnThisPcExtension implements BeforeAllCallback, AfterAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        HostExecution.allow(name(context));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        HostExecution.withdraw(name(context));
    }

    private static String name(ExtensionContext context) {
        Class<?> test = context.getRequiredTestClass();
        ModelCodeOnThisPc why = test.getAnnotation(ModelCodeOnThisPc.class);
        return "the test " + test.getSimpleName() + (why == null ? "" : " (" + why.value() + ")");
    }
}
