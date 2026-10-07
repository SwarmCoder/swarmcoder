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
 * How long ago, in the words a person uses.
 *
 * <p>It lives in the domain, beside {@link BuildHealth}, for the same reason that does: the agent
 * tools and the browser both have to say how long a worker has been going, and two spellings of
 * "3m" is two chances to disagree about whether something is alive. The MCP {@code swarm_status}
 * tool and the run graph now read the same method.
 */
public final class Elapsed {

    private Elapsed() { }

    /** "42s", "7m", "2h 5m", "3d 4h" — never a negative or a decimal. */
    public static String words(long millis) {
        if (millis < 0) {
            return "0s";
        }
        long seconds = millis / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h " + (minutes % 60) + "m";
        }
        return (hours / 24) + "d " + (hours % 24) + "h";
    }
}
