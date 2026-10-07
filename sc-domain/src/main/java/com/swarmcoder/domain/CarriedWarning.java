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

import java.time.Instant;
import java.util.Objects;

/**
 * An objection from an opinion check that would have parked the run, carried past instead because
 * nobody was there to ask (see {@link OpinionPolicy#WARN_AND_CARRY_ON}). Kept on the {@link Run}
 * so the end of the run, and the Console, can list every one: nothing is hidden.
 */
public class CarriedWarning {

    /** Which check objected, in words: "acceptance tests against the project's rules". */
    private String check;
    /** The stage the run was in, a {@link RunState} name. Text, so no stored enum is involved. */
    private String stage;
    /** The objection itself, exactly as the check gave it. */
    private String objection;
    private Instant at;

    public CarriedWarning() {}

    public CarriedWarning(String check, String stage, String objection, Instant at) {
        this.check = check;
        this.stage = stage;
        this.objection = objection;
        this.at = at;
    }

    public String check() { return check; }
    public String getCheck() { return check; }
    public String stage() { return stage; }
    public String getStage() { return stage; }
    public String objection() { return objection; }
    public String getObjection() { return objection; }
    public Instant at() { return at; }
    public Instant getAt() { return at; }

    /** One line for a log or a console: stage, check, objection. */
    public String oneLine() {
        return "[" + stage + "] " + check + ": "
            + (objection == null ? "" : objection.strip().replaceAll("\\s*\\R\\s*", " "));
    }

    // The time is left out on purpose: the same objection raised twice is the same warning.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CarriedWarning that)) return false;
        return Objects.equals(check, that.check) && Objects.equals(stage, that.stage)
            && Objects.equals(objection, that.objection);
    }

    @Override
    public int hashCode() {
        return Objects.hash(check, stage, objection);
    }
}
