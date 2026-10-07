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

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.teavm.jso.JSBody;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.events.Event;
import org.teavm.jso.dom.events.EventListener;
import org.teavm.jso.dom.html.HTMLElement;

/**
 * Browser clipboard access, and the one way a Copy control in this Console may be wired.
 *
 * <h2>Why {@link #onCopyClick} exists, and why an ordinary click listener will not do</h2>
 *
 * <p>A browser only lets a page write to the clipboard while it is still handling the click that
 * asked for it — the "transient activation" window. Every click listener registered through the
 * component library is wrapped in {@code Component.threaded(...)}, which is
 * {@code new Thread(() -> ...).start()}: the work is handed to the TeaVM scheduler and runs on a
 * LATER tick. By then the activation is gone, {@code document.execCommand('copy')} returns false,
 * and the button does nothing at all — no error, no message, nothing on the clipboard.
 *
 * <p>That wrapper is not a mistake: it is what lets a listener make a suspending RMI call. It is
 * simply the wrong wrapper for a copy button, which makes no server call and must not be deferred.
 * So the listener here is registered straight onto the element, unwrapped, and does nothing that
 * could suspend.
 *
 * <h2>Which of the two clipboard APIs, and why that order</h2>
 *
 * <p>{@code navigator.clipboard.writeText} first, {@code document.execCommand('copy')} only as a
 * fallback. That order was measured, not assumed: on this machine's Chromium
 * {@code document.execCommand('copy')} <b>returns true and copies nothing</b>, so a control built
 * on it reports success and leaves the clipboard empty — which is the second, independent reason
 * the component library's own copy buttons do not work.
 *
 * <p>{@code execCommand} is kept because {@code navigator.clipboard} does not exist on an insecure
 * origin, which is the Console served over plain HTTP on a LAN address. {@code http://localhost} is
 * a secure origin, so the ordinary local operator gets the reliable path.
 *
 * <p>The modern call is a promise, so the answer arrives later. The outcome is therefore delivered
 * to a callback rather than returned, and everything that reports "Copied" waits for it.
 */
final class Clipboard {

    private Clipboard() {}

    /** The copy is still in flight — the modern clipboard call is a promise. */
    private static final int PENDING = 2;
    /** It reached the clipboard. */
    private static final int COPIED = 1;
    /** The browser refused, both ways. */
    private static final int REFUSED = 0;

    /**
     * Starts a copy and says how it went, or that it is still going.
     *
     * <p>Must be CALLED from inside a real click handler — see the class javadoc.
     *
     * <p>The answer to a promise cannot be returned, so the JavaScript leaves it in one slot on
     * {@code window} and {@link #outcomeSoFar()} reads it. One slot rather than a keyed table
     * because a person presses one Copy button at a time; two presses inside the same few
     * milliseconds would report each other's answer, and neither answer would be wrong about
     * whether the clipboard took the text.
     *
     * @return {@link #COPIED}, {@link #REFUSED}, or {@link #PENDING}
     */
    @JSBody(params = {"text"}, script =
        // The older way, kept for insecure origins where navigator.clipboard does not exist.
        // Selecting a box takes the keyboard off whatever was just pressed, and removing the box
        // leaves it on nothing at all, so anybody who pressed Copy with the keyboard was dumped to
        // the top of the page — hence putting the focus back.
        "var byExecCommand = function() {"
        + "  var was = document.activeElement;"
        + "  var ok = false;"
        + "  try {"
        + "    var area = document.createElement('textarea');"
        + "    area.value = text;"
        + "    area.style.position = 'fixed';"
        + "    area.style.top = '0';"
        + "    area.style.opacity = '0';"
        + "    document.body.appendChild(area);"
        + "    area.focus();"
        + "    area.select();"
        + "    ok = document.execCommand('copy');"
        + "    document.body.removeChild(area);"
        + "  } catch (e) { ok = false; }"
        + "  if (was && was.focus) { try { was.focus(); } catch (ignored) {} }"
        + "  return ok ? 1 : 0;"
        + "};"
        + "window.__swarmcoderCopy = 2;"
        + "if (navigator.clipboard && window.isSecureContext) {"
        + "  try {"
        + "    navigator.clipboard.writeText(text).then("
        + "      function() { window.__swarmcoderCopy = 1; },"
        + "      function() { window.__swarmcoderCopy = byExecCommand(); });"
        + "    return 2;"
        + "  } catch (e) { }"
        + "}"
        + "window.__swarmcoderCopy = byExecCommand();"
        + "return window.__swarmcoderCopy;")
    private static native int startWrite(String text);

    /** {@link #COPIED}, {@link #REFUSED} or {@link #PENDING} for the copy started most recently. */
    @JSBody(params = {}, script =
        "return window.__swarmcoderCopy === undefined ? 0 : window.__swarmcoderCopy;")
    private static native int outcomeSoFar();

    /**
     * Selects an element's text in the page, so the operator can copy it by hand.
     *
     * <p>The answer to a refused clipboard. A Copy button that silently does nothing is worse than
     * no button; a Copy button that says "the text is selected, press Ctrl+C" and has selected it
     * still gets the operator the value.
     */
    @JSBody(params = {"element"}, script =
        "try {"
        + "  var selection = window.getSelection();"
        + "  var range = document.createRange();"
        + "  range.selectNodeContents(element);"
        + "  selection.removeAllRanges();"
        + "  selection.addRange(range);"
        + "  return true;"
        + "} catch (e) { return false; }")
    static native boolean select(HTMLElement element);

    /**
     * Wires a copy button, keeping the browser's user gesture intact.
     *
     * <p>The listener goes straight onto the element rather than through
     * {@code addClickListener}/{@code addDomEventListener}, both of which defer the work onto a
     * green thread and lose the gesture. Nothing inside it may suspend.
     *
     * @param button  the element that is pressed
     * @param text    what to copy, read at the moment of the press
     * @param outcome told true when the clipboard took it, false when the browser refused; it may
     *                be told on a later tick, because the modern clipboard call is a promise
     */
    static void onCopyClick(HTMLElement button, Supplier<String> text, Consumer<Boolean> outcome) {
        button.addEventListener("click", (EventListener<Event>) event -> {
            event.stopPropagation();
            String value = text.get();
            int status = startWrite(value == null ? "" : value);
            if (status == PENDING) {
                waitForOutcome(outcome, 0);
            } else {
                outcome.accept(status == COPIED);
            }
        });
    }

    /**
     * Looks again in a moment, until the promise has settled.
     *
     * <p>A timer rather than a callback out of the promise. A {@code @JSFunctor} called from inside
     * a settled promise was tried first and never reached Java at all: the copy worked and the word
     * "Copied" never appeared, which is the same silence this class exists to end. Timers are what
     * the rest of this client already uses to cross that boundary.
     *
     * <p>Gives up after half a second and reports a refusal, so a promise that never settles cannot
     * leave the operator with no answer.
     */
    private static void waitForOutcome(Consumer<Boolean> outcome, int attempt) {
        Window.setTimeout(() -> {
            int status = outcomeSoFar();
            if (status != PENDING) {
                outcome.accept(status == COPIED);
            } else if (attempt < 20) {
                waitForOutcome(outcome, attempt + 1);
            } else {
                outcome.accept(false);
            }
        }, 25);
    }

    /**
     * Makes the Copy button inside a component this Console did not build actually copy.
     *
     * <p>For {@code CodeBlock}, which draws its own Copy control and wires it the deferred way, so
     * it changes its label to "Copied" and puts nothing on the clipboard. The component cannot be
     * changed from here, so a second listener is added to the same button — an undeferred one that
     * does the copy while the gesture is still live. The component's own handler still runs
     * afterwards and still fails; by then the value is already on the clipboard.
     *
     * <p>Does nothing, loudly, if the component's shape changes and there is no button to repair:
     * a silent no-op here would put the dead button back without anybody noticing.
     */
    static void repairCopyButton(HTMLElement root, String text, String where) {
        HTMLElement button = firstButton(root);
        if (button == null) {
            ClientLog.warn("Clipboard", "no Copy button found inside " + where + " — its copy "
                + "control is the component library's own, which changes its label to \"Copied\" "
                + "and copies nothing");
            return;
        }
        onCopyClick(button, () -> text, copied -> {
            if (!copied) {
                ClientLog.warn("Clipboard", "the browser refused the clipboard for " + where
                    + " — the label will say \"Copied\" and nothing was copied");
            }
        });
    }

    @JSBody(params = {"root"}, script = "return root.querySelector('button');")
    private static native HTMLElement firstButton(HTMLElement root);
}
