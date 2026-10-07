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
 * Where a {@link Story} came from. {@link #BACKLOG}: planned deliberately from the requirement
 * graph. {@link #AD_HOC}: raised directly by a human outside planning. {@link #DISCOVERED}: found
 * mid-run — work that only became visible once the code was touched.
 */
public enum StoryOrigin {
    BACKLOG, AD_HOC, DISCOVERED;

    /** Where it came from, said rather than named. The story dialog used to show the constant. */
    public String label() {
        return switch (this) {
            case BACKLOG -> "planned from the requirements";
            case AD_HOC -> "raised by hand";
            case DISCOVERED -> "found while building";
        };
    }
}
