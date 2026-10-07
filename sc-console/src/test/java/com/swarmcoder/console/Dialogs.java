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

import com.microsoft.playwright.Page;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Waiting for a dialog to finish APPEARING before anyone — a person or a screenshot — looks at it.
 *
 * <p>This exists because of a bug report that was not a bug in the product. Two screenshots,
 * {@code console-brd.png} and {@code console-document-upload.png}, showed a dialog and the panel
 * behind it both legible at once, as though the dialog had no surface of its own. It has one. The
 * component library builds a daisyUI modal, and daisyUI opens one with a transition:
 *
 * <pre>
 *   .modal-box { background-color: var(--color-base-100); opacity: 0; scale: .95;
 *                transition: opacity .2s ease-out 50ms, scale .3s ease-out, ... }
 *   .modal.modal-open .modal-box { opacity: 1; scale: 1 }
 *   .modal.modal-open { background-color: oklch(0% 0 0 / .4) }   &lt;-- the dimming behind it
 * </pre>
 *
 * <p>So for the first ~350&nbsp;ms after it opens, the dialog really is part-transparent and the
 * page behind it really is not yet dimmed. Both screenshots were taken inside that window; the one
 * taken earlier shows a faint dialog over a crisp page, the one taken later a crisp dialog over a
 * page that shows through. Measured, not deduced: with the console's own stylesheets loaded, the
 * settled surface reports {@code background-color: oklch(0.2533 0.016 252.42)} — a colour with no
 * alpha at all — and {@code opacity: 1}. A screenshot of the same dialog taken after some other
 * interaction ({@code console-settings.png}) is completely opaque with the page behind it dimmed.
 *
 * <p>A screenshot is the only evidence anybody has for how a screen looks, so a screenshot taken
 * mid-transition is worse than no screenshot: it invents a defect. Every dialog screenshot goes
 * through {@link #settled(Page)}.
 *
 * <h2>What the native dialog changed here, and what it did not</h2>
 *
 * <p>Since ZeroZ Stack 0.8.0 a dialog is handed to the browser, which draws it in the <b>top
 * layer</b>. That is a painting order, not a move: the element stays exactly where it was in the
 * document, so {@code querySelectorAll('dialog.modal.modal-open')} still finds it, its
 * {@code .modal-box} is still its child, and {@code getAnimations} still reports the transition.
 * Everything below works unchanged, and it was checked rather than assumed.
 *
 * <p>What is new is that the browser paints a backdrop of its own behind a dialog it owns, on top
 * of the tint daisyUI already draws on the {@code .modal} element. Two dims over one page would
 * make the page behind a dialog markedly darker than it was designed to be, so
 * {@link #settled(Page)} now measures the pair and fails if they add up to more than one.
 */
final class Dialogs {

    private Dialogs() {
    }

    /**
     * Blocks until every open dialog has finished appearing, then proves the surface is opaque.
     *
     * <p>The wait is on the browser's own animation record rather than on a sleep: a fixed pause
     * is either too short on a loaded machine or wasted time on a fast one, and it can never say
     * WHY it waited. {@code getAnimations({subtree: true})} lists the running CSS transitions on
     * the dialog and everything inside it, so "nothing is still moving" is a fact the page reports
     * rather than a guess about a duration.
     *
     * <p>The assertion afterwards is the part that would catch the defect this was mistaken for.
     * If a future change did leave a dialog surface see-through — a lost background, a stray
     * translucent class, a width workaround that overwrites the class carrying the background —
     * every browser test that opens a dialog fails here with the colour it actually got.
     */
    static void settled(Page page) {
        page.waitForFunction(
            "() => {"
            + "  const open = [...document.querySelectorAll('dialog.modal.modal-open')];"
            + "  if (open.length === 0) return false;"
            + "  return open.every(m => {"
            + "    const box = m.querySelector('.modal-box');"
            + "    if (!box) return false;"
            + "    if (getComputedStyle(box).opacity !== '1') return false;"
            + "    return !m.getAnimations({subtree: true})"
            + "      .filter(a => a.constructor && a.constructor.name === 'CSSTransition')"
            + "      .some(a => a.playState === 'running');"
            + "  });"
            + "}",
            null,
            new Page.WaitForFunctionOptions().setTimeout(10_000));

        assertSingleDim(page);

        Object surface = page.evaluate(
            "() => {"
            + "  const box = document.querySelector('dialog.modal.modal-open .modal-box');"
            + "  const s = getComputedStyle(box);"
            + "  return s.backgroundColor + ' | opacity ' + s.opacity;"
            + "}");
        String description = String.valueOf(surface);
        assertThat(description)
            .describedAs("a dialog's own surface must be opaque, or the page behind it reads "
                + "straight through the words on it — measured colour was: " + description)
            .doesNotContain("transparent")
            .doesNotContain("opacity 0")
            // An alpha channel is written either as "rgba(r, g, b, 0.4)" or, in the modern colour
            // spaces daisyUI emits, as a "/ 0.4" suffix. A fully opaque colour has neither.
            .doesNotContain("/")
            .doesNotMatch("(?s).*rgba\\([^)]*,\\s*0(\\.\\d+)?\\).*");
    }

    /**
     * Proves the page behind a dialog is dimmed once rather than twice.
     *
     * <p>daisyUI tints the {@code .modal} element itself; the browser paints {@code ::backdrop}
     * behind a dialog it owns. Both cover the whole window, so if both carry a tint the page
     * behind gets both — 40% over 40% is 64%, and everything behind a dialog goes from "still
     * readable, clearly inactive" to nearly black. The two alphas are composited here the way the
     * screen composites them, and the total is held to one layer's worth.
     */
    private static void assertSingleDim(Page page) {
        Object dim = page.evaluate(
            "() => {"
            // No regex: this is a Java string holding JavaScript, and every backslash in it would
            // have to survive both languages. String operations say the same thing and read.
            + "  const alpha = (colour) => {"
            + "    if (!colour || colour === 'transparent') return 0;"
            + "    const inside = colour.slice(colour.indexOf('(') + 1, colour.lastIndexOf(')'));"
            // rgba(r, g, b, a) — the fourth part is the alpha, and rgb(...) has no fourth part.
            + "    if (colour.startsWith('rgb')) {"
            + "      const parts = inside.split(',');"
            + "      return parts.length > 3 ? parseFloat(parts[3]) : 1;"
            + "    }"
            // The modern colour spaces write it as 'oklch(0% 0 0 / 0.4)' or '/ 40%'.
            + "    const cut = inside.lastIndexOf('/');"
            + "    if (cut < 0) return 1;"
            + "    const written = inside.slice(cut + 1).trim();"
            + "    const value = parseFloat(written);"
            + "    return written.endsWith('%') ? value / 100 : value;"
            + "  };"
            + "  const open = [...document.querySelectorAll('dialog.modal.modal-open')];"
            + "  const last = open[open.length - 1];"
            + "  if (!last) return 'no open dialog';"
            + "  const own = getComputedStyle(last).backgroundColor;"
            + "  const back = getComputedStyle(last, '::backdrop').backgroundColor;"
            + "  const total = 1 - (1 - alpha(own)) * (1 - alpha(back));"
            + "  return total.toFixed(3) + ' from own ' + own + ' and backdrop ' + back;"
            + "}");
        String description = String.valueOf(dim);
        double total = description.startsWith("no ") ? 0
            : Double.parseDouble(description.substring(0, description.indexOf(' ')));
        assertThat(total)
            .describedAs("the page behind a dialog is dimmed once, not once by the stylesheet and "
                + "again by the browser's own backdrop — measured: " + description)
            .isLessThanOrEqualTo(0.55);
    }

    /**
     * Screenshots the settled dialog at a wide desktop, a laptop and a narrow window.
     *
     * <p>Three files, {@code <name>-1600.png}, {@code <name>-1280.png} and {@code <name>-960.png},
     * because a dialog that is right at 1600 can overflow its own box or push its buttons off the
     * bottom at 960 and nothing but looking will say so. The viewport is put back afterwards so the
     * rest of the test measures the size it was written for.
     */
    static void shotAtEveryWidth(Page page, String name) {
        shotAtEveryWidth(page, name, () -> { });
    }

    /**
     * As {@link #shotAtEveryWidth(Page, String)}, but runs a check at each of the three widths too.
     *
     * <p>A screenshot is evidence for a person; it is not a test. A layout that reads correctly at
     * 1600 and breaks at 960 comes back the moment nobody happens to open the 960 file, so a rule
     * worth pinning is pinned at every width the picture is taken at, in the same pass.
     */
    static void shotAtEveryWidth(Page page, String name, Runnable checkAtEachWidth) {
        for (int width : new int[] {1600, 1280, 960}) {
            page.setViewportSize(width, 950);
            settled(page);
            page.screenshot(new Page.ScreenshotOptions()
                .setPath(Path.of("target", name + "-" + width + ".png")));
            checkAtEachWidth.run();
        }
        page.setViewportSize(1600, 950);
        settled(page);
    }

    /**
     * Proves that nothing inside {@code selector} sticks out of it, and that it stays inside the
     * dialog it is in.
     *
     * <p>Written for the run graph's phase strip, which spent its whole life overflowing. It was six
     * fixed-width boxes drawn into an SVG canvas with one line of centred text in each, and SVG text
     * neither wraps nor clips: a phrase wider than its box painted straight over the boxes beside
     * it, and the strip as a whole was wider than the canvas so its last phase was cut off at the
     * dialog's edge. Neither fault could be seen by any assertion the suite had, because both are
     * facts about pixels, and both were present at every window size.
     *
     * <p>The measurement is a DOM {@code Range} over each child's own contents rather than
     * {@code scrollWidth}: a range reports where the words actually ended up, which is the thing
     * that was wrong, and it is not confused by the decorative pseudo-elements a stepper draws.
     *
     * <p>Children that paint nothing are passed over. An element with {@code display: none}
     * reports an empty rectangle at the top-left corner of the document, which is outside every
     * container on the page, so measuring it invents a fault. That is what happened when this
     * check — written for one SVG strip, all of whose children are drawn — was pointed at a
     * dialog: the component library emits a hidden {@code <h2>} for a title the wizard draws
     * itself, and the check reported the wizard as overflowing at every window width. Nothing was
     * wrong with the wizard.
     */
    static void assertNothingOverflows(Page page, String selector, String what) {
        Object faults = page.evaluate(
            "(sel) => {"
            + "  const strip = document.querySelector(sel);"
            + "  if (!strip) return 'NOT FOUND: ' + sel;"
            + "  const box = strip.getBoundingClientRect();"
            + "  const bad = [];"
            + "  for (const child of strip.children) {"
            + "    const cell = child.getBoundingClientRect();"
            // A child that paints nothing cannot stick out of anything. display:none reports an
            // empty box at the document origin, which is outside every strip there has ever been,
            // so measuring it reports a fault that is not on the screen. This is not theoretical:
            // it failed the intake wizard on a hidden <h2> the dialog component always emits.
            + "    if (cell.width === 0 && cell.height === 0) { continue; }"
            + "    const range = document.createRange();"
            + "    range.selectNodeContents(child);"
            + "    const words = range.getBoundingClientRect();"
            + "    const label = (child.textContent || '').trim();"
            + "    if (words.width > 0 && (words.right > cell.right + 1"
            + "        || words.left < cell.left - 1)) {"
            + "      bad.push('\"' + label + '\" is ' + Math.round(words.width)"
            + "        + 'px of words in a ' + Math.round(cell.width) + 'px cell');"
            + "    }"
            + "    if (cell.right > box.right + 1 || cell.left < box.left - 1) {"
            + "      bad.push('the cell for \"' + label + '\" sticks out of the strip');"
            + "    }"
            + "  }"
            + "  if (strip.scrollWidth > strip.clientWidth + 1) {"
            + "    bad.push('the strip scrolls sideways: ' + strip.scrollWidth"
            + "      + 'px of content in ' + strip.clientWidth + 'px');"
            + "  }"
            + "  const modal = strip.closest('.modal-box');"
            + "  if (modal) {"
            + "    const m = modal.getBoundingClientRect();"
            + "    if (box.right > m.right + 1 || box.left < m.left - 1) {"
            + "      bad.push('the strip sticks out of the dialog');"
            + "    }"
            + "  }"
            + "  return bad.join(' · ');"
            + "}",
            selector);
        assertThat(String.valueOf(faults))
            .describedAs(what + " must fit inside itself and inside its dialog at "
                + "every window width — what was measured: " + faults)
            .isEmpty();
    }
}
