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
package com.swarmcoder.store;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Literal guard for the EclipseStore record bug: walks the object graph reachable from
 * {@link StoreRoot} through declared fields (following generic type arguments) and fails if any
 * reachable {@code com.swarmcoder} type is a Java record — EclipseStore's reflective serializer
 * cannot persist records, they must be mutable POJOs.
 *
 * <p>Complements {@code StoreArchitectureTest.PERSISTED_DOMAIN_TYPES_ARE_NOT_RECORDS}: that rule
 * scans the whole domain package (catching payloads hidden behind {@code Lazy<Object>}, whose
 * element type erasure makes them invisible to this reflective walk — exactly where
 * {@code AgentSessionRecord} lived). This test additionally catches a record wired into StoreRoot
 * from OUTSIDE the domain package, which the package-scoped rule would miss.
 */
class StoreRootReachabilityTest {

    @Test
    void noTypeReachableFromStoreRootIsARecord() {
        Set<Class<?>> visited = new HashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        List<String> offenders = new ArrayList<>();

        visited.add(StoreRoot.class);
        queue.add(StoreRoot.class);
        while (!queue.isEmpty()) {
            Class<?> type = queue.poll();
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                for (Class<?> referenced : swarmClassesIn(field.getGenericType())) {
                    if (referenced.isRecord()) {
                        offenders.add(referenced.getName() + " (reachable from StoreRoot via "
                            + type.getSimpleName() + "." + field.getName() + ")");
                    }
                    if (visited.add(referenced)) {
                        queue.add(referenced); // recurse into its fields
                    }
                }
            }
        }

        assertThat(offenders)
            .as("types reachable from StoreRoot must be mutable POJOs, not records — "
                + "EclipseStore cannot persist records (see VerificationReport)")
            .isEmpty();
    }

    /** Every concrete {@code com.swarmcoder} class named anywhere in a (possibly generic) type. */
    private static List<Class<?>> swarmClassesIn(Type type) {
        List<Class<?>> out = new ArrayList<>();
        collect(type, out, new HashSet<>());
        return out;
    }

    private static void collect(Type type, List<Class<?>> out, Set<Type> seen) {
        if (type == null || !seen.add(type)) {
            return;
        }
        switch (type) {
            case Class<?> c -> {
                if (c.isArray()) {
                    collect(c.getComponentType(), out, seen);
                } else if (c.getName().startsWith("com.swarmcoder") && !c.isEnum() && !c.isInterface()) {
                    out.add(c);
                }
            }
            case ParameterizedType p -> {
                collect(p.getRawType(), out, seen);
                for (Type arg : p.getActualTypeArguments()) {
                    collect(arg, out, seen);
                }
            }
            case GenericArrayType g -> collect(g.getGenericComponentType(), out, seen);
            case WildcardType w -> {
                for (Type bound : w.getUpperBounds()) {
                    collect(bound, out, seen);
                }
            }
            default -> { /* TypeVariable and friends carry no concrete class reference */ }
        }
    }
}
