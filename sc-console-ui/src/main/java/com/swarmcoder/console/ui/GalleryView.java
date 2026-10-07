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

import com.zeroz4j.ui.component.CodeBlock;
import com.zeroz4j.ui.component.ContextMenu;
import com.zeroz4j.ui.component.DiffView;
import com.zeroz4j.ui.component.EmptyState;
import com.zeroz4j.ui.component.KpiTile;
import com.zeroz4j.ui.component.MarkdownView;
import com.zeroz4j.ui.component.PropertyGrid;
import com.zeroz4j.ui.component.Sparkline;
import com.zeroz4j.ui.component.SplitPane;
import com.zeroz4j.ui.component.StatusDot;
import com.zeroz4j.ui.component.StreamingText;
import com.zeroz4j.ui.component.SvgCanvas;
import com.zeroz4j.ui.component.TokenMeter;
import com.zeroz4j.ui.component.VirtualScroller;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import com.zeroz4j.signals.ValueSignal;
import org.teavm.jso.browser.Window;
import org.teavm.jso.dom.xml.Element;

import java.util.ArrayList;
import java.util.List;
import com.zeroz4j.ui.component.Component;
import java.util.function.Supplier;

/**
 * Component gallery (design C2-0 acceptance): every new component rendered with sample
 * data — the visual regression surface and the TeaVM reachability anchor. Dev-only tab.
 */
final class GalleryView extends Div {

    GalleryView() {
        addClassName("p-6 space-y-6 max-w-5xl");

        section("StatusDot — the state vocabulary", () -> {
            Div row = new Div();
            row.addClassName("flex items-center gap-4 flex-wrap");
            for (String state : new String[]{"RUNNING", "SURVIVED", "SELECTED", "FAILED",
                    "KILLED", "SUPERSEDED", "PENDING"}) {
                Div item = new Div();
                item.addClassName("flex items-center gap-1.5 text-sm");
                item.add(new StatusDot(state));
                Span label = new Span(state);
                item.getElement().appendChild(label.getElement());
                row.add(item);
            }
            return row;
        });

        section("KpiTile + Sparkline + TokenMeter", () -> {
            Div row = new Div();
            row.addClassName("flex items-end gap-4 flex-wrap");
            row.add(new KpiTile("Survival rate").value("78%").delta("▲ 12% vs last run", true)
                .trend(new double[]{0.4, 0.55, 0.5, 0.62, 0.7, 0.68, 0.78}));
            row.add(new KpiTile("Tokens / run").value("46.3K").delta("▼ 8%", true)
                .trend(new double[]{80, 72, 65, 60, 52, 49, 46}));
            TokenMeter meter = new TokenMeter();
            meter.set(1_200_000, 3_000_000);
            TokenMeter hot = new TokenMeter();
            hot.set(2_850_000, 3_000_000);
            Div meters = new Div();
            meters.addClassName("flex flex-col gap-2 pb-2");
            meters.add(meter, hot);
            row.add(meters);
            return row;
        });

        section("MarkdownView (untrusted input, text-node construction)", () ->
            new MarkdownView("""
                ## Design summary
                The architect proposes **two tasks** with *disjoint* write sets:

                - `Calculator.java` — add `multiply(int, int)`
                - `CalculatorTest.java` — unit test

                | task | writeSet | criteria |
                | --- | --- | --- |
                | multiply | src/main/... | 2 |

                > Red-check must pass before dispatch. <script>alert('escaped, not executed')</script>

                ```java
                public int multiply(int a, int b) {
                    return a * b; // 42
                }
                ```
                """));

        section("CodeBlock (tokenizer) + copy", () -> {
            String code = """
                /* verification pipeline */
                public boolean survived(VerificationReport report) {
                    // fail fast on compile
                    if (!report.compiles()) return false;
                    return report.acceptance().failed() == 0 && "green".equals(status);
                }
                """;
            CodeBlock block = new CodeBlock("java", code, true);
            // Its own Copy control says "Copied" and copies nothing; repaired the way every
            // CodeBlock in the Console is. See Clipboard.repairCopyButton.
            Clipboard.repairCopyButton(block.getElement(), code, "the gallery's code block");
            return block;
        });

        section("DiffView (unified diff)", () ->
            new DiffView("""
                diff --git a/src/main/java/com/example/calc/Calculator.java b/src/main/java/com/example/calc/Calculator.java
                --- a/src/main/java/com/example/calc/Calculator.java
                +++ b/src/main/java/com/example/calc/Calculator.java
                @@ -1,4 +1,5 @@
                 package com.example.calc;
                 public class Calculator {
                     public int add(int a, int b) { return a + b; }
                +    public int multiply(int a, int b) { return a * b; }
                 }
                """));

        // Drawn the way the Console draws one: the grid is the framework's, every value in it is
        // a CopyValue. The framework's own string row carries a copy button that does nothing, so
        // a gallery using it would be showing developers the broken shape as the example.
        section("PropertyGrid + CopyValue (the button copies; the value is select-all)",
            () -> new PropertyGrid()
            .row("run", new CopyValue("run", "9e714c82-284f-4c14-aed7-97a43da3b8b4"))
            .row("prefixHash", new CopyValue("prefixHash", "56beae52100ea083c701f6396ac17d9a"))
            .row("state", new StatusDot("RUNNING")));

        section("VirtualScroller — 5,000 rows, windowed", () -> {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < 5000; i++) {
                items.add("TraceEvent #" + i + " — TOOL_CALL exec: mvn -q -B compile");
            }
            VirtualScroller<String> scroller = new VirtualScroller<>(28, text -> {
                Div row = new Div(text);
                row.addClassName("px-3 text-xs font-mono flex items-center border-b "
                    + "border-base-300/40 text-base-content/70");
                return row;
            });
            scroller.addClassName("h-48 border border-base-300 rounded-lg");
            scroller.setItems(items);
            return scroller;
        });

        section("StreamingText (live token stream)", () -> {
            ValueSignal<String> stream = new ValueSignal<>("");
            StreamingText streaming = new StreamingText().bind(stream);
            String message = "The swarm dispatched 10 workers across the temperature range; "
                + "verification-first selection keeps only candidates that compile and pass "
                + "the acceptance suite...";
            tick(stream, message, 0);
            return streaming;
        });

        section("SvgCanvas — pan (drag) / zoom (wheel), graph preview", () -> {
            SvgCanvas canvas = new SvgCanvas();
            canvas.addClassName("h-56 border border-base-300 rounded-lg bg-base-200/40");
            drawPreviewGraph(canvas);
            return canvas;
        });

        section("ContextMenu (right-click) + EmptyState", () -> {
            EmptyState empty = new EmptyState("graph", "Right-click me",
                "The context menu component — row-specific items supported.");
            ContextMenu menu = new ContextMenu()
                .item("play", "Open run graph", () -> { })
                .item("copy", "Copy id", () -> { })
                .item("x", "Close", () -> { });
            menu.attachTo(empty, null);
            return empty;
        });

        section("SplitPane (drag the divider — position persists)", () -> {
            SplitPane split = SplitPane.horizontal("gallery-demo", 220, 120, 500);
            split.addClassName("h-32 border border-base-300 rounded-lg");
            Div left = new Div("left (fixed px)");
            left.addClassName("p-4 text-sm text-base-content/60");
            Div right = new Div("right (flex)");
            right.addClassName("p-4 text-sm text-base-content/60");
            split.setFirst(left);
            split.setSecond(right);
            return split;
        });
    }

    private void section(String title, Supplier<Component> content) {
        Div titleDiv = new Div(title);
        titleDiv.addClassName("text-sm font-semibold text-base-content/60 mt-2");
        add(titleDiv);
        add(content.get());
    }

    private static void tick(ValueSignal<String> stream, String message, int i) {
        if (i >= message.length()) {
            Window.setTimeout(() -> {
                stream.set("");
                tick(stream, message, 0);
            }, 3000);
            return;
        }
        stream.set(message.substring(0, i + 1));
        Window.setTimeout(() -> tick(stream, message, i + 1), 25);
    }

    /** A miniature of the C2-3 run graph so SvgCanvas interactions can be felt today. */
    private static void drawPreviewGraph(SvgCanvas canvas) {
        String[][] nodes = {
            {"60", "90", "DESIGN", "#22c55e"}, {"210", "90", "PLAN", "#22c55e"},
            {"360", "40", "task A", "#38bdf8"}, {"360", "140", "task B", "#94a3b8"},
            {"520", "20", "w0 ✓", "#22c55e"}, {"520", "60", "w1 ✗", "#f59e0b"},
            {"520", "100", "w2 ♛", "#eab308"},
        };
        int[][] edges = {{0, 1}, {1, 2}, {1, 3}, {2, 4}, {2, 5}, {2, 6}};
        for (int[] edge : edges) {
            Element path = SvgCanvas.el("path",
                "d", "M " + (Integer.parseInt(nodes[edge[0]][0]) + 90) + " "
                    + (Integer.parseInt(nodes[edge[0]][1]) + 14)
                    + " C " + (Integer.parseInt(nodes[edge[0]][0]) + 140) + " "
                    + (Integer.parseInt(nodes[edge[0]][1]) + 14) + ", "
                    + (Integer.parseInt(nodes[edge[1]][0]) - 50) + " "
                    + (Integer.parseInt(nodes[edge[1]][1]) + 14) + ", "
                    + nodes[edge[1]][0] + " " + (Integer.parseInt(nodes[edge[1]][1]) + 14),
                "fill", "none", "stroke", "#64748b", "stroke-width", "1.5");
            canvas.viewport().appendChild(path);
        }
        for (String[] node : nodes) {
            Element rect = SvgCanvas.el("rect",
                "x", node[0], "y", node[1], "width", "90", "height", "28",
                "rx", "8", "fill", "transparent", "stroke", node[3], "stroke-width", "2");
            Element text = SvgCanvas.el("text",
                "x", String.valueOf(Integer.parseInt(node[0]) + 45),
                "y", String.valueOf(Integer.parseInt(node[1]) + 18),
                "text-anchor", "middle", "fill", "currentColor", "font-size", "12");
            text.appendChild(Window.current().getDocument().createTextNode(node[2]));
            canvas.viewport().appendChild(rect);
            canvas.viewport().appendChild(text);
        }
    }
}

