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
 * Lifecycle of a {@link GuidedFlow}. {@link #DRAFT}: collecting inputs — documents and their notes
 * — nothing has been asked of the agent yet. {@link #RUNNING}: the agent is working; this is real
 * server state, not a spinner. {@link #AWAITING_ANSWERS}: a round of {@link FlowQuestion}s is open
 * and the operator's answers are needed. {@link #REVIEW}: {@link FlowProposal}s are ready to be
 * accepted or rejected. {@link #APPLIED}: the accepted proposals have landed in the BRD.
 * {@link #FAILED}: the run ended badly and {@code error} says why.
 *
 * <p>The normal path is {@code DRAFT -> RUNNING -> AWAITING_ANSWERS -> REVIEW -> APPLIED}, with
 * {@link #FAILED} reachable from any working state. From any resting state the operator may return
 * to {@link #DRAFT} to add a document and run again — re-analysis merges against what already
 * exists and shows the diff, so returning to DRAFT never discards prior work.
 */
public enum GuidedFlowState { DRAFT, RUNNING, AWAITING_ANSWERS, REVIEW, APPLIED, FAILED }
