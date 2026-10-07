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
 * What kind of thing a requirement states.
 *
 * <p>{@link #FUNCTIONAL}: behaviour the system must exhibit — what it does.
 * {@link #NON_FUNCTIONAL}: a quality constraint on how it must do it, further classified by
 * {@link NfrCategory}, with fitness criteria that gate delivery. Both are DELIVERABLE: somebody
 * builds them, a task claims them, a test proves them, and one day they are done.
 *
 * <p><b>{@link #CONSTRAINT} is dead, and only the constant survives.</b> For a few hours on
 * 2026-08-31 a standing rule about how the project is built — "this is pure Java, there is no SQL
 * and no Spring anywhere in it" — was filed as a third kind of requirement. It was the wrong home
 * and the code said so: nothing delivers such a rule, so it needed exempting from the plan's
 * coverage rule, from the agreement gate, from every coverage figure, from story slicing, from the
 * backlog, and from four separate screens. Nine exemptions to keep a thing out of machinery it
 * should never have entered.
 *
 * <p>Rules live where SwarmCoder already kept rules: {@link LearnedGuideline}, which has a scope, a
 * status, a provenance, files as its editing surface, a screen that turns one on and off, and a
 * command that can prove one was obeyed. A technical document is ingested straight into those.
 *
 * <p><b>The constant stays because the value is persisted.</b> Nothing creates a new one — not the
 * intake, not the BRD-author agent, not the editor — but a store written that afternoon may hold
 * requirements saying CONSTRAINT, and removing the constant would make them unreadable. They now
 * behave as ordinary requirements with no checks: visible, labelled as leftovers, and safe to
 * retire once the same rules have been stated as guidelines.
 */
public enum RequirementKind {
    FUNCTIONAL, NON_FUNCTIONAL, CONSTRAINT
}
