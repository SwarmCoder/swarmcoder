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
package com.swarmcoder.app.config;

/**
 * Unattended running: the operator plans a set of stories, presses go, and goes to bed.
 *
 * <p>Off by default, and it stays off by default. The rule it works alongside says the definition of
 * done is a human one, and the reason for that rule is still true — "the tests pass" and "this is
 * what I asked for" are different claims. Switching this on says: for one session, when the machine
 * has proved the first claim completely and honestly, treat a human verdict as the rubber stamp it
 * would have been, accept the story, put its code with the rest of the project, and start the next
 * story that was waiting for it. Anything less than unambiguous stops and waits for the morning.
 *
 * @param enabled              whether the machine may accept finished stories and start the next
 *                             ones on its own
 * @param maxConcurrentStories how many stories may be building at once. One by default, and one is
 *                             the right answer overnight: two stories building at the same time are
 *                             each cut from the branch as it was before either started, so neither
 *                             can see the other's work and the second only meets it at the merge.
 *                             Speed is not the point here; a queue that can be understood in the
 *                             morning is.
 */
public record OvernightConfig(boolean enabled, Integer maxConcurrentStories) {

    public OvernightConfig(boolean enabled) {
        this(enabled, null);
    }

    /** How many stories may build at once — never less than one, whatever the file says. */
    public int concurrencyOrDefault() {
        return maxConcurrentStories == null || maxConcurrentStories < 1 ? 1 : maxConcurrentStories;
    }
}
