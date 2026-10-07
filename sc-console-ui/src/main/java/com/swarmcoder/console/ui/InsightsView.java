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

import com.swarmcoder.console.api.InsightsDto;
import com.swarmcoder.console.api.ObserverService;
import com.swarmcoder.console.api.ObserverService_Stub;
import com.zeroz4j.ui.theme.TextStyle;
import com.zeroz4j.ui.chart.MetricTable;
import com.zeroz4j.ui.chart.PanelFrame;
import com.zeroz4j.ui.chart.ScatterChart;
import com.zeroz4j.ui.chart.ValueFormat;
import com.zeroz4j.ui.component.Alert;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.component.EmptyState;
import com.zeroz4j.ui.component.KpiTile;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Insights v2 (design §5.4 / §8): the eval dataset made visible. KPI tiles up top, then the
 * temperature-vs-survival scatter — survival rate per temperature, one bubble per model family and
 * temperature, sized by how many candidates the rate is drawn from — which answers "what sampling
 * temperature actually codes best" straight from the archived candidates. Family and kill-reason
 * tables round it out.
 *
 * <p>All of it used to be drawn by hand into a bare {@code SvgCanvas} on a fixed 780x320 viewBox:
 * gridlines, axis ticks, bubbles, {@code <title>} tooltips, a swatch legend, and a CSS grid
 * pretending to be a table. ZeroZ Stack ships every one of those — {@link ScatterChart},
 * {@link MetricTable}, {@link PanelFrame}, {@link KpiTile} — so about 150 lines of coordinate
 * arithmetic are gone, and the screen gained axis bounds chosen to land on round numbers, a
 * tooltip that follows the pointer, and a chart that resizes with its container.
 *
 * <p><b>One thing was lost in the swap</b>, and is deliberately not hand-drawn back: the faint line
 * joining one family's bubbles in temperature order. {@code ScatterChart} plots points, not
 * per-category trend lines. The bubbles carry the same numbers; the line only made their ordering
 * easier to follow.
 */
final class InsightsView extends Div {

    private final ObserverService observer = new ObserverService_Stub();
    private final ValueSignal<InsightsDto> insights = new ValueSignal<>(null);
    /**
     * A failed load leaves the signal null, and the body would then say "Loading insights..." for
     * the rest of the page's life, because nothing retries. This says the loading has stopped.
     */
    private final Alert error = new Alert("", "alert-error");

    InsightsView() {
        addClassName("flex flex-col gap-5");

        Div header = new Div();
        header.addClassName("flex items-center gap-3");
        Span title = new Span("Insights");
        title.addClassName("text-lg font-bold");
        Span sub = new Span("the eval dataset, made visible");
        TextStyle.CAPTION.applyTo(sub);
        Div spacer = new Div();
        spacer.addClassName("flex-1");
        Button refresh = new Button("Refresh", e -> refresh());
        refresh.addClassName("btn-xs btn-ghost");
        header.add(title, sub, spacer, refresh);
        add(header);

        error.addClassName("text-xs py-2");
        error.setVisible(false);
        add(error);

        Div body = new Div();
        body.addClassName("flex flex-col gap-5");
        add(body);

        Effect.create(() -> {
            body.removeAll();
            InsightsDto i = insights.get();
            if (i == null) {
                body.add(new EmptyState("chart", "Loading insights...",
                    "Aggregates over archived candidates and sessions."));
                return;
            }
            body.add(kpiTiles(i));
            body.add(scatterPanel(i));
            Div tables = new Div();
            tables.addClassName("grid grid-cols-1 md:grid-cols-2 gap-4");
            tables.add(familyPanel(i.getFamilyRows()));
            tables.add(killReasonPanel(i.getKillReasonRows()));
            body.add(tables);
        });

        refresh();
    }

    // --- KPI tiles -------------------------------------------------------------------------------

    private Div kpiTiles(InsightsDto i) {
        int survived = sumColumn(i.getFamilyRows(), 2);
        int dispatched = sumColumn(i.getFamilyRows(), 1);
        String survivalPct = dispatched == 0 ? "-"
            : Math.round(100.0 * survived / dispatched) + "%";

        Div grid = new Div();
        grid.addClassName("grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-3");
        grid.add(new KpiTile("Runs").value(String.valueOf(i.getTotalRuns())));
        grid.add(new KpiTile("Candidates").value(String.valueOf(i.getTotalCandidates())));
        grid.add(new KpiTile("Survival").value(survivalPct));
        grid.add(new KpiTile("Selected").value(String.valueOf(i.getSelectedCandidates())));
        grid.add(new KpiTile("Sessions").value(String.valueOf(i.getTotalSessions())));
        grid.add(new KpiTile("Tokens").value(compact(i.getTotalTokens())));
        return grid;
    }

    // --- scatter ---------------------------------------------------------------------------------

    private PanelFrame scatterPanel(InsightsDto i) {
        PanelFrame panel = new PanelFrame("Temperature vs survival");
        panel.setSubtitle("survival rate per sampling temperature, by model family");

        Map<String, TreeMap<Double, int[]>> byFamily = parseScatter(i.getScatterRows());
        if (byFamily.isEmpty()) {
            panel.setNoDataText("Once runs archive candidates across temperatures, the survival "
                + "rates appear here.");
            panel.setState(PanelFrame.State.NO_DATA);
            return panel;
        }

        List<ScatterChart.Point> points = new ArrayList<>();
        for (Map.Entry<String, TreeMap<Double, int[]>> family : byFamily.entrySet()) {
            for (Map.Entry<Double, int[]> point : family.getValue().entrySet()) {
                int total = point.getValue()[0];
                int survived = point.getValue()[1];
                double ratePercent = total == 0 ? 0 : 100.0 * survived / total;
                // The bubble's AREA is how many candidates the rate is drawn from, which is what
                // stops one lucky sample at an extreme temperature reading like a finding.
                points.add(new ScatterChart.Point(point.getKey(), ratePercent, total,
                    family.getKey()));
            }
        }

        ScatterChart chart = new ScatterChart();
        chart.setYFormat(ValueFormat.PERCENT);
        chart.setYBounds(0, 100);
        chart.setAxisLabels("temperature", "survived");
        chart.setRadiusRange(4, 16);
        chart.setChartHeight(280);
        chart.setPoints(points);
        panel.setContent(chart);
        return panel;
    }

    // --- tables ----------------------------------------------------------------------------------

    private PanelFrame familyPanel(String rows) {
        MetricTable<String[]> table = new MetricTable<>();
        table.addTextColumn("family", row -> cell(row, 0));
        table.addValueColumn("dispatched", row -> number(row, 1), ValueFormat.INTEGER);
        table.addValueColumn("survived", row -> number(row, 2), ValueFormat.INTEGER);
        table.addValueColumn("selected", row -> number(row, 3), ValueFormat.INTEGER);
        table.setEmptyText("no data");
        table.setItems(parseRows(rows));
        return panel("Survival by model family", table);
    }

    private PanelFrame killReasonPanel(String rows) {
        MetricTable<String[]> table = new MetricTable<>();
        table.addTextColumn("reason", row -> cell(row, 0));
        table.addValueColumn("count", row -> number(row, 1), ValueFormat.INTEGER);
        table.setEmptyText("no data");
        table.setItems(parseRows(rows));
        return panel("Kill reasons", table);
    }

    private PanelFrame panel(String title, MetricTable<String[]> table) {
        PanelFrame frame = new PanelFrame(title);
        frame.setDense(true);
        frame.setContent(table);
        return frame;
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** Tab-separated rows, one per line, as the observer service sends them. */
    private static List<String[]> parseRows(String rows) {
        List<String[]> parsed = new ArrayList<>();
        if (rows != null) {
            for (String line : rows.split("\n")) {
                if (!line.isBlank()) {
                    parsed.add(line.split("\t"));
                }
            }
        }
        return parsed;
    }

    private static String cell(String[] row, int column) {
        return column < row.length ? row[column] : "";
    }

    private static double number(String[] row, int column) {
        try {
            return Double.parseDouble(cell(row, column).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** family -&gt; (temperature -&gt; [total, survived]). */
    private static Map<String, TreeMap<Double, int[]>> parseScatter(String scatterRows) {
        Map<String, TreeMap<Double, int[]>> byFamily = new LinkedHashMap<>();
        if (scatterRows == null) {
            return byFamily;
        }
        for (String line : scatterRows.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length < 3) {
                continue;
            }
            double temp;
            try {
                temp = Math.round(Double.parseDouble(parts[0]) * 100.0) / 100.0;
            } catch (NumberFormatException e) {
                continue;
            }
            boolean survived = "1".equals(parts[1]);
            String family = parts[2];
            int[] stat = byFamily.computeIfAbsent(family, k -> new TreeMap<>())
                .computeIfAbsent(temp, k -> new int[2]);
            stat[0]++;
            if (survived) {
                stat[1]++;
            }
        }
        return byFamily;
    }

    private static int sumColumn(String rows, int column) {
        int total = 0;
        if (rows != null) {
            for (String line : rows.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (column < parts.length) {
                    try {
                        total += Integer.parseInt(parts[column].trim());
                    } catch (NumberFormatException ignored) {
                        // skip malformed row
                    }
                }
            }
        }
        return total;
    }

    private static String compact(long n) {
        if (n >= 1_000_000) {
            return Math.round(n / 100_000.0) / 10.0 + "M";
        }
        if (n >= 1_000) {
            return Math.round(n / 100.0) / 10.0 + "k";
        }
        return String.valueOf(n);
    }

    private void refresh() {
        try {
            insights.set(observer.insights());
            error.setVisible(false);
        } catch (Exception ex) {
            // System.out in the compiled client reaches only a devtools console, so this used
            // to leave a permanent "Loading insights..." that nothing was ever going to replace.
            error.setText("Insights could not be loaded. Press Refresh to try again.");
            error.setVisible(true);
            ClientLog.error("InsightsView", "insights failed to load - the page keeps saying "
                + "it is loading and no figures will appear: " + ex);
        }
    }
}
