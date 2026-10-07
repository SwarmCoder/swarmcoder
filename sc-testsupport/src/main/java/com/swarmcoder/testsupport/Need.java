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

/**
 * A thing a test needs from outside the build before it can say anything true.
 *
 * <p>Two kinds live in here and the difference is the whole point of this enum.
 *
 * <p><b>Tools that are simply present or absent</b> — {@link #CHROMIUM}, {@link #DOCKER},
 * {@link #MAVEN}. A test needing one of these runs in an ordinary build on any machine that has
 * it, with no flag to remember, and is skipped — loudly, see {@link NotRun} — on a machine that
 * does not. Nobody has to opt in, so nobody can forget to.
 *
 * <p><b>Things that must never become a condition of building</b> — {@link #LIVE_MODEL},
 * {@link #PAID_CLOUD_MODELS}, {@link #DEMO_REPO}. These stay opt-in on purpose, and each says
 * below why. They are skipped by default, and the skip is announced in exactly the same way, so a
 * green build still tells you they did not run.
 */
public enum Need {

    /**
     * Playwright's Chromium, which the browser tests drive. Detected by looking for a
     * {@code chromium*} directory in Playwright's browser store, so an ordinary developer machine
     * that has ever run {@code playwright install} runs these tests without being asked.
     */
    CHROMIUM,

    /**
     * A Docker daemon that answers, and the {@code swarmcoder-worker} image built. Both are
     * checked, because a daemon without the image fails in a way that reads like a product fault.
     */
    DOCKER,

    /** Maven on the PATH — for the tests that shell out to a real build to see what it emits. */
    MAVEN,

    /**
     * A model server, named with {@code -Dswarmcoder.live.baseUrl=...}.
     *
     * <p><b>This gate stays.</b> The only model server on this network is shared with other work
     * and is not always up. Making a build depend on it would mean a build that fails for reasons
     * that have nothing to do with the code, and a build that quietly loads somebody else's
     * hardware. The live journey is a thing a person runs deliberately, not a build step.
     */
    LIVE_MODEL,

    /**
     * The operator's own cloud model accounts, switched on with {@code SWARMCODER_CONFIG_E2E=true}.
     *
     * <p><b>This gate stays, and is the one gate that must never be relaxed.</b> The test spends
     * real money on every run. A build must not be able to bill anybody.
     */
    PAID_CLOUD_MODELS,

    /**
     * A target repository to verify, named with {@code -Dswarmcoder.demo.repo=...}.
     *
     * <p><b>This gate stays for now,</b> for a plain reason: the repository it wants is not in
     * this checkout. {@code dev/demo-repo} is referred to by the test and by several documents and
     * does not exist here, so there is nothing to point the property at unless the developer has
     * their own copy. If that repository is ever committed, this need should become a
     * present-or-absent tool like the three above.
     */
    DEMO_REPO
}
