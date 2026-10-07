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
package com.swarmcoder.swarm;

import com.swarmcoder.domain.KillReason;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The stop signal of one dispatch group, or of one candidate. When a candidate is selected while
 * siblings are still running (repair waves), or a spare candidate is no longer wanted, the signal
 * is set and every in-flight worker that carries it is killed by its TurnGuard on its next turn
 * — freed capacity instead of wasted decode (spec §11.3).
 *
 * <p>The signal carries WHY (2026-10-02): {@link KillReason#SUPERSEDED} when another attempt on
 * the same task already passed, {@link KillReason#PLACE_NEEDED} when a spare attempt's place was
 * needed by a task that had nothing running. The first reason given stands.
 */
public final class GroupSignal {

    private final AtomicReference<KillReason> stopped = new AtomicReference<>();

    public void supersede() {
        stop(KillReason.SUPERSEDED);
    }

    /**
     * Stops every worker carrying this signal at its next turn, with {@code reason}.
     *
     * @return false when it was already stopped, for whatever reason
     */
    public boolean stop(KillReason reason) {
        KillReason why = reason == null ? KillReason.SUPERSEDED : reason;
        if (!stopped.compareAndSet(null, why)) {
            return false;
        }
        // Told now, not at each worker's next turn (live run 74: a task waited twelve minutes
        // for a stopped worker's request to come back).
        for (java.util.function.Consumer<KillReason> listener : listeners) {
            try {
                listener.accept(why);
            } catch (RuntimeException e) {
                // a listener that cannot stop its worker leaves it to stop at its next turn
            }
        }
        return true;
    }

    private final java.util.List<java.util.function.Consumer<KillReason>> listeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Calls {@code listener} when the workers carrying this signal are stopped - at once when
     * they already were. Returns what takes the listener off again.
     */
    public Runnable onStop(java.util.function.Consumer<KillReason> listener) {
        listeners.add(listener);
        KillReason already = stopped.get();
        if (already != null) {
            listener.accept(already);
        }
        return () -> listeners.remove(listener);
    }

    public boolean isSuperseded() {
        return stopped.get() != null;
    }

    /** Why the workers carrying this signal are being stopped; null while they are not. */
    public KillReason reason() {
        return stopped.get();
    }
}
