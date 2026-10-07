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
 * What the browser stage actually DID, as opposed to what it found.
 *
 * <p>This exists for the same reason {@link TestStageOutcome} does, and it draws the same line the
 * model-endpoint classifier draws between "the model said no" and "the model was not there" (see
 * {@code EndpointOutage}). Before this field, every browser-stage failure looked identical: the
 * page list came back with every page marked not-loaded and one "infrastructure" assertion on each,
 * and {@code Verdicts} killed the candidate. That is right when the candidate's application refused
 * to start. It is badly wrong when the harness never got as far as trying — no headless browser
 * installed on this machine, no background-process support on this execution target, the fixed port
 * already taken by something else. In that case nothing whatever was learned about the candidate,
 * and failing it would park every run for a reason that has nothing to do with the code.
 *
 * <p><b>APPEND ONLY.</b> These are persisted with every {@link BrowserCheckResults}; inserting a
 * constant in the middle re-labels every verification report already in the store.
 */
public enum BrowserStageOutcome {

    /**
     * The harness started the application (or served the static directory), drove a real browser at
     * it, and the page results are the truth — including a failure to load, which means the
     * candidate's application did not come up or did not render.
     */
    EXECUTED,

    /**
     * The harness could not get far enough to learn anything: no browser to drive, no way to start a
     * background process on this target, the port already in use, the static server refused to bind.
     * <b>This must never fail a candidate.</b> It is an absent instrument, not a verdict.
     */
    COULD_NOT_TRY
}
