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
package com.swarmcoder.server.mcp;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Stand-ins for the three Console services, built by reflection rather than by hand.
 *
 * <p>{@code ControlService} alone has thirty methods and the other two are growing; writing three
 * literal fakes would be four hundred lines of boilerplate that stops compiling the moment another
 * session adds a method to an interface these tests do not care about. A proxy answers the handful
 * of calls a test names and returns a harmless empty value for everything else.
 */
final class Stubs {

    private Stubs() { }

    static final class Builder<T> {
        private final Class<T> type;
        private final Map<String, Function<Object[], Object>> answers = new HashMap<>();

        Builder(Class<T> type) {
            this.type = type;
        }

        Builder<T> answer(String method, Function<Object[], Object> body) {
            answers.put(method, body);
            return this;
        }

        Builder<T> returning(String method, Object value) {
            return answer(method, args -> value);
        }

        @SuppressWarnings("unchecked")
        T build() {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
                (proxy, method, args) -> {
                    Function<Object[], Object> body = answers.get(method.getName());
                    if (body != null) {
                        return body.apply(args == null ? new Object[0] : args);
                    }
                    return empty(method.getReturnType());
                });
        }
    }

    static <T> Builder<T> of(Class<T> type) {
        return new Builder<>(type);
    }

    private static Object empty(Class<?> returnType) {
        if (returnType == List.class) {
            return List.of();
        }
        if (returnType == String.class) {
            return "";
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == void.class) {
            return null;
        }
        return null;
    }
}
