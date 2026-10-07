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
 * What a run does when an OPINION check — a model's judgement, which can be wrong — has used its
 * one bounded correction attempt and still objects (owner decision, 2026-10-01).
 *
 * <p>SwarmCoder has two kinds of checks. A FACT check is mechanical and cannot be wrong: the code
 * compiles, a test fails, a type exists, a plan has tasks. An OPINION check is a model reading
 * something against the project's rules and objecting. A wrong opinion used to park a run nobody
 * was watching (live harness runs 53, 55 and 60). This policy decides that one thing, for every
 * opinion check, in one place; fact checks stop the run under either value.
 *
 * <p>Never stored: it is how a run is being driven, not something about the run. What IS stored
 * is every objection carried past under {@link #WARN_AND_CARRY_ON} — see {@link CarriedWarning}.
 */
public enum OpinionPolicy {
    /** A person is at the console: park the run and ask them (the product's default). */
    ASK_THE_OPERATOR,
    /**
     * Nobody is watching (the live harness): record the objection as a warning on the run, log
     * it, and carry on. A question about a rule is answered by rewording the rule to allow what
     * the evidence showed, as before.
     */
    WARN_AND_CARRY_ON
}
