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
 * {@code ABANDONED} (added 2026-09-03): a run whose PROJECT no longer exists — the project was
 * deleted while the run was unfinished, or the run is a pre-existing orphan a deleted project left
 * behind before deletion cleaned up after itself. Terminal, exactly like {@code ABORTED}: the
 * workflow will never resume it and {@code RunResumer} will never hand it to an engine. Kept apart
 * from {@code ABORTED} because "the operator rejected this" and "the project this belonged to is
 * gone" are different facts, and a run log that says ABORTED for the second would be lying about
 * why. Appended at the end — this enum is persisted by name, but ordinal-sensitive code elsewhere
 * must not have a new terminal value inserted ahead of it.
 */
public enum RunState { INTAKE, DESIGN, DESIGN_REVIEW, PLAN, TEST_AUTHORING, REPRODUCE, CHARACTERIZE, EXECUTING, FINAL_INTEGRATION, APPROVAL, DELIVERED, ABORTED, ABANDONED }
