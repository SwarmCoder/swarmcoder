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
package com.swarmcoder.workflow;

import com.swarmcoder.knowledge.LookupAgent;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The role sessions that handed in and were kept, by what they handed in (section 54): when that
 * hand-in is rejected, the rejection goes to the same conversation instead of a new session that
 * reads the project again. A role keeps its own instance; nothing is shared between roles.
 *
 * <p>Held in memory only: after a restart, or when an entry is evicted, a retry simply opens a
 * fresh session, which is what every retry did before. Bounded, so a long run does not pile up
 * conversations nobody will resume; the oldest is closed first.
 */
final class KeptConversations {

    /** What is kept: the conversation and the role's own tools that were bound into it. */
    record Held(LookupAgent.Conversation conversation, Object tools) {}

    static final int MAX_KEPT = 8;

    private final Map<String, Held> held = new LinkedHashMap<>();

    /** Keeps {@code entry} under {@code key}; whatever was under that key is closed. */
    synchronized void keep(String key, Held entry) {
        Held replaced = held.remove(key);
        if (replaced != null) {
            replaced.conversation().close();
        }
        held.put(key, entry);
        for (Iterator<Held> oldest = held.values().iterator();
             held.size() > MAX_KEPT && oldest.hasNext(); ) {
            Held evicted = oldest.next();
            oldest.remove();
            evicted.conversation().close();
        }
    }

    /** Hands out what is kept under {@code key} and forgets it; null when there is nothing. */
    synchronized Held take(String key) {
        return held.remove(key);
    }

    synchronized int size() {
        return held.size();
    }
}
