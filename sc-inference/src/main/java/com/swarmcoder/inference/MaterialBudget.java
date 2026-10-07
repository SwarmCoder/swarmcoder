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
package com.swarmcoder.inference;

/**
 * How much reference material a model's working room affords — the one rule every fixed
 * character cap on handed-out material is scaled by.
 *
 * <p><b>Why this exists (2026-09-25).</b> Every cap on what the librarian, the help desk, the
 * expert and the architect hand a model was written as a constant, and every one of them was
 * measured on the Qwen workers of August and September 2026, whose room was 51,200 tokens (see
 * {@code ServerCapabilities.derivedWorkingContextTokens} and the Librarian's own measurement: a
 * 16,030-character brief was 7.8% of that room). When the workers moved to DeepSeek V4 Flash on the
 * Spark ({@code ModelShapes} {@code deepseek-v4-flash-ds4}) their room became 262,144 tokens — five
 * times more — because on Qwen they thrashed, compacting and re-reading, and the zeroz4j reference
 * material they need is big. The room grew; the material handed into it did not. A worker with a
 * quarter of a million tokens of room was still handed an 18,000-character brief and a
 * 6,000-character {@code lookup_api} answer sized for a model that could hold a fifth as much.
 *
 * <h2>The rule</h2>
 *
 * <pre>
 *   chars(baseline) = baseline * clamp(room, BASELINE_TOKENS, CEILING_TOKENS) / BASELINE_TOKENS
 * </pre>
 *
 * <ul>
 *   <li><b>Linear.</b> Every one of these caps was chosen as a share of a measured room — the brief
 *       at under a tenth of it, a lookup answer at about 3% of it per question. Scaling linearly
 *       keeps every measured share exactly what it was, so nothing that was tuned against the
 *       room changes its relationship to the room.</li>
 *   <li><b>Baseline 51,200 tokens, and never below it.</b> 51,200 is the room every one of these
 *       numbers was measured in, so today's constants are exactly the result at 51,200. A SMALLER
 *       room keeps today's figures too, deliberately. The cloud roles — the architect and the
 *       utility role the expert runs on — are configured on the generic shape at 32,768 tokens and
 *       run today with exactly these figures; shrinking them would change a working production
 *       path with no measurement behind the change. {@code ChangeNeighbourhood.forWorkingContext}
 *       does shrink, because design §2.2 budgeted that one channel explicitly as a share of the
 *       window; nothing here was budgeted that way below the room it was measured in.</li>
 *   <li><b>Ceiling 262,144 tokens</b> — a factor of 5.12. At the ceiling the worker's brief may be
 *       92,160 characters, about 23,000 tokens: 8.8% of the room, the same share as today, leaving
 *       the other nine tenths for the work, and far under the 75% high-water mark at which
 *       {@code HistoryTrim} starts emptying tool results. Beyond it nothing is measured: 262,144 is
 *       the largest room any shape in this codebase has given one worker, and the material itself
 *       runs out around there — the worked example's neighbour walk stops at 60,000 bytes
 *       ({@code WorkedExamples.NEIGHBOUR_BUDGET_BYTES}), the whole catalogue of zeroz4j's 45
 *       documents fits in about 7,000 characters at the 150 or so a line costs, and a lookup answer already carries all three of
 *       the files and both of the sections its selection allows. A 1,048,576-token model scaled
 *       linearly would be handed a 368,640-character brief (about 92,000 tokens) prefilled into
 *       every worker, and decoded against at every turn, for ceilings that nothing reaches. The
 *       Spark's decode speed at depth is the thing to watch before raising this.</li>
 * </ul>
 *
 * <h2>Which room</h2>
 *
 * <p>The room of the model that will READ the material, never another's. The worker's brief and
 * its {@code lookup_api} answers are sized by the workers' room (the smallest of the worker
 * families, because one brief is shared by every worker of a task); the architect's reference and
 * research results by the architect's own model; the expert's tool results and the context handed
 * to it by the expert's own model. The room is {@link ModelQuirks#workingContextTokens()} as
 * resolved at startup — for a worker, the figure {@code ServerCapabilities} derived from the
 * server, or the shape's own.
 *
 * <h2>What does NOT scale</h2>
 *
 * <p>Only caps on material a model reads. A cap that bounds a log line, a command echo, an excerpt
 * shown to a human, a count (files, sections, refs, research rounds), a ranking constant or a
 * shaping threshold is not about a model's room and stays a constant where it is.
 *
 * @param workingContextTokens the reader's working room as configured or discovered; 0 or less
 *                             means "not known", which is the baseline
 */
public record MaterialBudget(int workingContextTokens) {

    /** The room every scaled constant was measured in: the Qwen workers, August–September 2026. */
    public static final int BASELINE_TOKENS = 51_200;

    /** Above this, material stops growing. See the class note for why 262,144. */
    public static final int CEILING_TOKENS = 262_144;

    /** Today's figures, exactly — what every caller that states no room gets. */
    public static final MaterialBudget BASELINE = new MaterialBudget(BASELINE_TOKENS);

    /** The budget for a reader whose room is {@code workingContextTokens}; 0 or less is the baseline. */
    public static MaterialBudget forWorkingContext(int workingContextTokens) {
        return workingContextTokens <= 0 ? BASELINE : new MaterialBudget(workingContextTokens);
    }

    /** The budget for a model with these quirks; null quirks are the baseline. */
    public static MaterialBudget of(ModelQuirks quirks) {
        return quirks == null ? BASELINE : forWorkingContext(quirks.workingContextTokens());
    }

    /**
     * The budget for the model behind this client — the room its own role was configured or
     * discovered with. A null client, or a stand-in with no quirks, is the baseline.
     */
    public static MaterialBudget of(VllmClient client) {
        return client == null ? BASELINE : of(client.quirks());
    }

    /** The room the rule actually uses: clamped to [{@link #BASELINE_TOKENS}, {@link #CEILING_TOKENS}]. */
    public int effectiveTokens() {
        return Math.max(BASELINE_TOKENS, Math.min(CEILING_TOKENS, workingContextTokens));
    }

    /**
     * A cap written for the baseline room, sized for this one. Exactly {@code baselineChars} at or
     * below 51,200 tokens; 5.12 times it at or above 262,144.
     */
    public int chars(int baselineChars) {
        if (baselineChars <= 0) {
            return baselineChars;
        }
        return (int) ((long) baselineChars * effectiveTokens() / BASELINE_TOKENS);
    }

    /** True when every cap is today's figure — a room at or below the baseline. */
    public boolean atBaseline() {
        return effectiveTokens() == BASELINE_TOKENS;
    }

    /** The scale factor, for a log line. */
    public double factor() {
        return effectiveTokens() / (double) BASELINE_TOKENS;
    }

    /** One sentence for a startup or ledger line. */
    public String describe() {
        if (atBaseline()) {
            return "reference material at the measured sizes (room " + workingContextTokens
                + " tokens, at or below the " + BASELINE_TOKENS + " the sizes were measured in)";
        }
        return String.format(java.util.Locale.ROOT,
            "reference material at %.2f times the measured sizes (room %d tokens%s)",
            factor(), workingContextTokens,
            workingContextTokens > CEILING_TOKENS
                ? ", held at the " + CEILING_TOKENS + "-token ceiling" : "");
    }
}
