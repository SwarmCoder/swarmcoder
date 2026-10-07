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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code com.swarmcoder.domain} type as a transient computation result — built, read, and
 * discarded within one call, never stored as a field of {@code StoreRoot} or of anything reachable
 * from it — so it is exempt from the "no records in the domain package" rule that EclipseStore's
 * inability to persist records otherwise forces on the whole package.
 *
 * <p>This is a narrow escape hatch, not a way around the rule: every use is checked by hand against
 * the object graph EclipseStore actually walks (see {@code StoreArchitectureTest} and
 * {@code StoreRootReachabilityTest} in {@code sc-store}), and {@code value()} has to say, in plain
 * words, why the type can never reach the store. A type that starts riding on something persisted
 * has to drop this annotation and become a mutable POJO instead — the reachability test is the
 * backstop if that ever happens without anyone noticing.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NotReachableFromStoreRoot {

    /** Why this type is computed and discarded rather than persisted. */
    String value();
}
