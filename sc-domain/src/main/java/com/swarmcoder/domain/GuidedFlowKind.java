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
 * What a {@link GuidedFlow} is for. {@link #REQUIREMENTS_INTAKE}: uploaded documents plus their
 * per-document notes in, requirement and criterion proposals out. {@link #BACKLOG_PLANNING}: the
 * ACTIVE requirements in, story proposals out. {@link #DELIVERY}: a READY story in, a run and then
 * an acceptance decision out.
 *
 * <p>The three share one machinery on purpose — three wizards that behaved differently would
 * recreate, in a new form, the confusion the guided-flow surface exists to remove.
 */
public enum GuidedFlowKind { REQUIREMENTS_INTAKE, BACKLOG_PLANNING, DELIVERY }
