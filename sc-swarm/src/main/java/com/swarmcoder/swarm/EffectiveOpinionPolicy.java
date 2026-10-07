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

import com.swarmcoder.domain.OpinionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Works out, at the moment of a decision, which {@link OpinionPolicy} is in force (owner decision,
 * 2026-10-01). A person at the console gets {@code ASK_THE_OPERATOR}. Anything unattended gets
 * {@code WARN_AND_CARRY_ON}: the live test harness (set explicitly), the overnight setting, and an
 * autonomous "build all of this" session. The last two are asked each time rather than copied, so
 * the policy follows them as they are switched on and off while the app runs, and one ending can
 * never switch the other off.
 *
 * <p>Every change of the effective policy is logged once, with the reason.
 */
public final class EffectiveOpinionPolicy {

    private static final Logger log = LoggerFactory.getLogger(EffectiveOpinionPolicy.class);

    private volatile OpinionPolicy explicit = OpinionPolicy.ASK_THE_OPERATOR;
    private volatile Supplier<String> unattendedReason = () -> null;
    private volatile String lastLogged = null;

    /** What the harness (or a test) set directly; null means ask the operator. */
    public void setExplicit(OpinionPolicy policy) {
        this.explicit = policy == null ? OpinionPolicy.ASK_THE_OPERATOR : policy;
    }

    /**
     * @param reason says why nobody is at the console right now (for example "the overnight
     *               setting is on"), or returns null/blank when a person is. Called at every
     *               decision, so it must be cheap and must read the live state.
     */
    public void setUnattendedReason(Supplier<String> reason) {
        this.unattendedReason = reason == null ? () -> null : reason;
    }

    public OpinionPolicy get() {
        String why;
        OpinionPolicy policy;
        if (explicit == OpinionPolicy.WARN_AND_CARRY_ON) {
            policy = OpinionPolicy.WARN_AND_CARRY_ON;
            why = "it was set directly (an unattended harness run)";
        } else {
            String reason = null;
            try {
                reason = unattendedReason.get();
            } catch (RuntimeException e) {
                log.warn("Could not tell whether anyone is at the console; assuming someone is", e);
            }
            if (reason == null || reason.isBlank()) {
                policy = OpinionPolicy.ASK_THE_OPERATOR;
                why = "a person is at the console";
            } else {
                policy = OpinionPolicy.WARN_AND_CARRY_ON;
                why = reason;
            }
        }
        String now = policy + " because " + why;
        String before = lastLogged;
        if (!now.equals(before)) {
            synchronized (this) {
                if (!now.equals(lastLogged)) {
                    lastLogged = now;
                    if (before != null || policy == OpinionPolicy.WARN_AND_CARRY_ON) {
                        log.info("Opinion policy is now {}: {}", policy, why);
                    }
                }
            }
        }
        return policy;
    }
}
