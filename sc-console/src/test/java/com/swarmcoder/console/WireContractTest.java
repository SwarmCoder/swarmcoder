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
package com.swarmcoder.console;

import com.swarmcoder.console.api.BacklogService;
import com.swarmcoder.console.api.BrdService;
import com.swarmcoder.console.api.ChatService;
import com.swarmcoder.console.api.ControlService;
import com.swarmcoder.console.api.GraphService;
import com.swarmcoder.console.api.GuidedFlowService;
import com.swarmcoder.console.api.HealthService;
import com.swarmcoder.console.api.ObserverService;
import com.swarmcoder.console.api.PlanningFlowService;
import com.zeroz4j.api.DataModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every domain type an RMI service can put on the wire carries {@code @DataModel}.
 *
 * <p>Without the annotation the annotation processor generates no serializer, and the failure is
 * invisible until a browser actually calls the method: the frame dies with "Unsupported type for
 * GrowableBuffer", the panel shows nothing, and nothing at build time said a word. That is exactly
 * what happened to {@code Decision} — a service was migrated to return the domain object instead of
 * a DTO and the class was never annotated, so the Approval Center could not load at all, silently,
 * for as long as nobody opened it.
 *
 * <p>Reflection over the service interfaces is the only way to catch it: the mistake is an ABSENCE,
 * so there is no code to review and no call site to notice. This test walks the signatures the same
 * way the serializer does and fails at build time instead.
 */
class WireContractTest {

    /**
     * The {@code @RmiService} interfaces. Listed rather than scanned so that adding a service is a
     * deliberate line in this file — a new service that nobody added here would otherwise be
     * exempt from the check that exists to protect it.
     */
    private static final List<Class<?>> SERVICES = List.of(
        BacklogService.class, BrdService.class, ChatService.class, ControlService.class,
        GraphService.class, GuidedFlowService.class, HealthService.class, ObserverService.class,
        PlanningFlowService.class);

    @Test
    void everyDomainTypeOnTheWireIsADataModel() {
        Set<Class<?>> unannotated = new LinkedHashSet<>();
        for (Class<?> service : SERVICES) {
            for (Method method : service.getMethods()) {
                List<Type> types = new ArrayList<>();
                types.add(method.getGenericReturnType());
                types.addAll(List.of(method.getGenericParameterTypes()));
                for (Type type : types) {
                    for (Class<?> carried : classesIn(type)) {
                        if (needsSerializer(carried) && !carried.isAnnotationPresent(DataModel.class)) {
                            unannotated.add(carried);
                        }
                    }
                }
            }
        }
        assertThat(unannotated)
            .as("these cross the wire but have no generated serializer, so the call fails at the "
                + "frame the first time a browser makes it — annotate them @DataModel")
            .isEmpty();
    }

    /** The concrete classes a signature carries, unwrapping one level of generics (List, Map). */
    private static Set<Class<?>> classesIn(Type type) {
        Set<Class<?>> found = new LinkedHashSet<>();
        if (type instanceof Class<?> raw) {
            found.add(raw);
        } else if (type instanceof ParameterizedType parameterized) {
            if (parameterized.getRawType() instanceof Class<?> raw) {
                found.add(raw);
            }
            for (Type argument : parameterized.getActualTypeArguments()) {
                found.addAll(classesIn(argument));
            }
        }
        return found;
    }

    /**
     * Whether the serializer needs a generated handler for this type.
     *
     * <p>Only {@code com.swarmcoder.domain} classes: the wire aggregates in {@code console.api} are
     * checked by the same rule through their own signatures, primitives and {@code String} are
     * built in, and an ENUM is registered by name rather than by a generated serializer — so an
     * un-annotated enum is not the bug this exists to catch.
     */
    private static boolean needsSerializer(Class<?> type) {
        return type.getName().startsWith("com.swarmcoder.domain.")
            && !type.isEnum() && !type.isPrimitive() && !type.isInterface();
    }
}
