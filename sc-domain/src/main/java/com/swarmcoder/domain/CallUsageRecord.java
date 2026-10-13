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
 * One model call of a run, as the usage ledger kept it: who made it, what it was for and the
 * model server's own counts for it. Read from a run's {@link CallLog}; never stored itself.
 *
 * <p>A count the server did not report is -1, so a reader can say "not recorded" and never
 * shows a 0 that looks like a measurement.
 *
 * @param role          the role, as the call was tagged
 * @param task          the task the call was made for (its id as text), or null
 * @param worker        the worker's index as text, or null
 * @param model         the model's id
 * @param endpointClass {@link ModelUsageRecord#CLOUD} or {@link ModelUsageRecord#LOCAL}
 * @param purpose       what the call was for, in the few fixed words its caller chose
 * @param candidate     the candidate the call worked on (its id as text), or null
 * @param startedAt     when the request was sent, in milliseconds since 1970, to the second
 * @param millis        how long the call took, or -1 when its end was not recorded
 * @param input         tokens sent, or -1
 * @param cachedInput   how many of {@code input} the server took from its prompt cache, or -1
 * @param output        tokens written, thinking included, or -1
 * @param thinking      how many of {@code output} were thinking, or -1
 * @param failed        the call ended without an answer
 * @param estimated     a count here is an estimate from the size of the text (characters / 4)
 *                      because the server gave none
 */
public final class CallUsageRecord {
    private final String role;
    private final String task;
    private final String worker;
    private final String model;
    private final String endpointClass;
    private final String purpose;
    private final String candidate;
    private final long startedAt;
    private final long millis;
    private final long input;
    private final long cachedInput;
    private final long output;
    private final long thinking;
    private final boolean failed;
    private final boolean estimated;

    public CallUsageRecord(String role, String task, String worker, String model, String endpointClass, String purpose, String candidate, long startedAt, long millis, long input, long cachedInput, long output, long thinking, boolean failed, boolean estimated) {
        this.role = role;
        this.task = task;
        this.worker = worker;
        this.model = model;
        this.endpointClass = endpointClass;
        this.purpose = purpose;
        this.candidate = candidate;
        this.startedAt = startedAt;
        this.millis = millis;
        this.input = input;
        this.cachedInput = cachedInput;
        this.output = output;
        this.thinking = thinking;
        this.failed = failed;
        this.estimated = estimated;
    }

    public String role() { return role; }
    public String task() { return task; }
    public String worker() { return worker; }
    public String model() { return model; }
    public String endpointClass() { return endpointClass; }
    public String purpose() { return purpose; }
    public String candidate() { return candidate; }
    public long startedAt() { return startedAt; }
    public long millis() { return millis; }
    public long input() { return input; }
    public long cachedInput() { return cachedInput; }
    public long output() { return output; }
    public long thinking() { return thinking; }
    public boolean failed() { return failed; }
    public boolean estimated() { return estimated; }

    /** True unless the call went to the operator's own server. */
    public boolean paid() {
        return !ModelUsageRecord.LOCAL.equals(endpointClass);
    }
}
