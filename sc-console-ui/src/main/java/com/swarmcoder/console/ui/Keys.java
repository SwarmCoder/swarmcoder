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
package com.swarmcoder.console.ui;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.dom.events.KeyboardEvent;

/**
 * The key a keyboard event carries, as a string that is safe to call methods on.
 *
 * <h2>The fault this exists for</h2>
 *
 * <p>{@code KeyboardEvent.getKey()} is a JSO property read, and a keydown event does not always
 * have one: a synthetic event, an event a component re-dispatched, and some IME and autofill
 * events all arrive with {@code key} <b>undefined</b>. TeaVM converts a JavaScript value to a Java
 * string with {@code $rt_str}, which is
 *
 * <pre>str =&gt; str === null ? null : new String(str)</pre>
 *
 * <p>— it tests for {@code null} and nothing else. {@code undefined} is not {@code null}, so it
 * builds a {@code java.lang.String} object whose backing {@code nativeString} is {@code undefined}.
 * That object is not null in Java, passes every null check written against it, and then throws
 * {@code TypeError: Cannot read properties of undefined (reading 'length')} out of
 * {@code jl_String_length} the first time anything asks it for its length — which is every
 * {@code equals}, {@code equalsIgnoreCase}, {@code hashCode} and {@code switch} on it.
 *
 * <p>It is unfixable on the Java side: the value is already a broken String by the time Java sees
 * it, so no {@code == null} guard can catch it and no String method can be called to test it. The
 * only place to stop it is before the conversion, in JavaScript, which is what this does.
 *
 * <p>The console logged fifteen of these in one afternoon, all from the Ctrl+K command-palette
 * shortcut bound to the document — so every keystroke anywhere in the Console went through it.
 */
final class Keys {

    private Keys() { }

    /**
     * The event's key, or {@code ""} when it has none.
     *
     * <p>Never returns a string that throws when used, which is the entire point.
     */
    static String of(KeyboardEvent event) {
        return event == null ? "" : keyOf(event);
    }

    @JSBody(params = "event", script =
        "var k = event && event.key; return typeof k === 'string' ? k : '';")
    private static native String keyOf(JSObject event);
}
