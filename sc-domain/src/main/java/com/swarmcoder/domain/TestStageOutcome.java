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
package com.swarmcoder.domain;

/**
 * What a verification test stage actually DID, as opposed to what it counted.
 *
 * <p>This exists because "no failures" and "nothing ran" are the same number, and telling them
 * apart is the whole of the fix recorded in docs/DEVELOPER_CORRECTIONS.md §17.1. Until 2026-08-27
 * every acceptance stage in the project selected zero tests, reported zero failures, and every
 * candidate survived it — for weeks, in silence. Counts alone cannot say whether a stage was never
 * asked to run, ran and found nothing to run, or ran and could not be read.
 *
 * <p><b>APPEND ONLY.</b> These are persisted with every {@link TestResults}; inserting a constant
 * in the middle re-labels every verification report already in the store.
 */
public enum TestStageOutcome {

    /**
     * The stage was never attempted, because an earlier stage failed and short-circuited it. It
     * says nothing about this stage, and the candidate is already dead on the earlier one.
     */
    SKIPPED,

    /**
     * The verification contract ({@code .swarmcoder/verify.yaml}) declares no commands for this
     * stage. Nothing could have run, whatever the repository contains.
     */
    NOT_CONFIGURED,

    /**
     * The commands ran to completion and their result was read. <b>The counts are the truth</b> —
     * including a count of zero, which means the runner genuinely executed no tests.
     */
    EXECUTED,

    /**
     * The commands ran but no trustworthy result came back — the exec target was unreachable, or a
     * failing command produced no report to read. Nothing may be concluded either way.
     *
     * <p>This is also how a report written before this field existed reads, which is deliberate: an
     * old report must never be mistaken for one that positively established "zero tests ran".
     */
    INCONCLUSIVE
}
