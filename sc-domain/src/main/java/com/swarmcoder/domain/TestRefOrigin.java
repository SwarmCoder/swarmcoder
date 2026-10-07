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
 * Who wrote the test reference on an {@link AcceptanceCriterion} — the one string on which the
 * whole requirement-to-commit trace hangs.
 *
 * <p>Until 2026-08-27 there was only one answer: a person typed it into the Requirements editor,
 * and nothing checked it against anything. The requirements wizard now proposes a reference
 * alongside each check it proposes, which is a suggestion and not a decision — so the editor has to
 * be able to say which is which. A proposed reference is a name nobody has agreed to yet; an
 * operator's is a decision. Rendering them identically would quietly turn the wizard's guess into
 * the operator's commitment.
 *
 * <p><b>APPEND ONLY.</b> Persisted with every criterion.
 */
public enum TestRefOrigin {

    /**
     * A person typed it. This is also how a reference with no recorded origin reads: everything
     * written before the wizard could propose one was typed by hand, so that is the honest default
     * rather than crediting the wizard with somebody else's work.
     */
    OPERATOR,

    /**
     * The requirements wizard proposed it, following the project's acceptance-test convention. It
     * is a name for a test that does not exist yet, and the operator may change it.
     */
    PROPOSED
}
