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
 * What a {@link FlowProposal} would do to the BRD. {@link #ADD}: a new requirement that does not
 * exist yet. {@link #EDIT}: a change to an existing one, carried as {@code before} and
 * {@code after} so it can be shown as a diff. {@link #DEPRECATE}: an existing requirement the
 * documents no longer support. {@link #CONFLICT}: two requirements that cannot both hold — nothing
 * to apply, a contradiction to resolve.
 *
 * <p>Nothing here deletes. A superseded requirement is marked {@link RequirementStatus#DEPRECATED}
 * and keeps its handle, its history and every link the backlog and the commit trail hold against
 * it; removing the row would break traceability for work that has already shipped, and leave a
 * dangling citation wherever it was referenced.
 *
 * <p><b>APPEND ONLY.</b> EclipseStore persists an enum by its ORDINAL, so inserting or reordering a
 * constant silently re-points every value already on disk. It refuses to open the store rather than
 * corrupt it, which means the application does not start at all. {@code DEPRECATE} sits last for
 * that reason, not because it reads well there. New constants go on the end — always.
 */
public enum FlowProposalKind { ADD, EDIT, CONFLICT, DEPRECATE }
