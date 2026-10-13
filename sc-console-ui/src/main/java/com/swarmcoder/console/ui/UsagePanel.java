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

import com.swarmcoder.console.api.ObserverService;
import com.swarmcoder.console.api.ObserverService_Stub;
import com.swarmcoder.console.api.UsageTableDto;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;
import com.zeroz4j.ui.chart.MetricTable;
import com.zeroz4j.ui.chart.PanelFrame;
import com.zeroz4j.ui.component.Alert;
import com.zeroz4j.ui.component.Button;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import com.zeroz4j.ui.theme.TextStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * What the model calls of one run, or of the selected project, came to per role: the figures
 * of the supervisor's {@code run_report} and {@code project_report}, as two plain tables.
 *
 * <p>Until section 102 of the developer corrections the Console showed none of this: a person
 * could read a build's tokens per role only by asking a supervisor tool. The warnings stand
 * first because they are what must not be missed; then calls and tokens per role with the
 * middle and the largest call; then what each role looked up, by kind, and how many of those
 * lookups asked what had already been answered in the run.
 */
final class UsagePanel extends Div {

    private final ObserverService observer = new ObserverService_Stub();
    private final ValueSignal<UsageTableDto> usage = new ValueSignal<>(null);
    private final String runId;
    private final Alert error = new Alert("", "alert-error");

    /** @param runId the run to show, or "" for the selected project */
    UsagePanel(String runId) {
        this.runId = runId == null ? "" : runId;
        addClassName("flex flex-col gap-4");

        Div header = new Div();
        header.addClassName("flex items-center gap-3");
        Span title = new Span("Model calls per role");
        title.addClassName("text-sm font-bold");
        Span sub = new Span(this.runId.isEmpty() ? "every run of this project, and what was "
            + "called for no run" : "this run");
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
        body.addClassName("flex flex-col gap-4");
        add(body);

        Effect.create(() -> {
            body.removeAll();
            UsageTableDto dto = usage.get();
            if (dto == null) {
                return;
            }
            if (dto.getNote() != null && !dto.getNote().isBlank()) {
                body.add(lines(dto.getNote(), "text-xs whitespace-pre-wrap"));
                return;
            }
            for (String warning : nonBlankLines(dto.getWarnings())) {
                Alert alert = new Alert(warning, "alert-warning");
                alert.addClassName("text-xs py-2");
                body.add(alert);
            }
            if (dto.getTotals() != null && !dto.getTotals().isBlank()) {
                body.add(lines(dto.getTotals(), "text-xs"));
            }
            body.add(rolePanel(dto.getRoleRows()));
            if (dto.getUsed() != null && !dto.getUsed().isBlank()) {
                body.add(lines(dto.getUsed(), "text-xs whitespace-pre-wrap"));
            }
            body.add(lookupPanel(dto.getLookupRows()));
        });

        refresh();
    }

    private PanelFrame rolePanel(String rows) {
        MetricTable<String[]> table = new MetricTable<>();
        table.addTextColumn("role", row -> cell(row, 0));
        table.addTextColumn("server", row -> cell(row, 1));
        table.addTextColumn("calls", row -> cell(row, 2));
        table.addTextColumn("input tokens", row -> cell(row, 3));
        table.addTextColumn("cached input", row -> cell(row, 4));
        table.addTextColumn("output tokens", row -> cell(row, 5));
        table.addTextColumn("of it thinking", row -> cell(row, 6));
        table.addTextColumn("median input of a call", row -> cell(row, 7));
        table.addTextColumn("largest input of one call", row -> cell(row, 8));
        table.addTextColumn("the largest call was for", row -> cell(row, 9));
        table.addTextColumn("seconds in calls", row -> cell(row, 10));
        table.addTextColumn("calls without an answer", row -> cell(row, 11));
        table.setEmptyText("no model call was recorded");
        table.setItems(parseRows(rows));
        PanelFrame frame = new PanelFrame("Calls and tokens");
        frame.setSubtitle("each call counted by the model server itself");
        frame.setDense(true);
        frame.setContent(table);
        return frame;
    }

    private PanelFrame lookupPanel(String rows) {
        MetricTable<String[]> table = new MetricTable<>();
        table.addTextColumn("role", row -> cell(row, 0));
        table.addTextColumn("kind of lookup", row -> cell(row, 1));
        table.addTextColumn("lookups", row -> cell(row, 2));
        table.addTextColumn("characters returned", row -> cell(row, 3));
        table.addTextColumn("repeats", row -> cell(row, 4));
        table.addTextColumn("characters of the repeats", row -> cell(row, 5));
        table.addTextColumn("about the reference material", row -> cell(row, 6));
        table.setEmptyText("no lookup was recorded");
        table.setItems(parseRows(rows));
        PanelFrame frame = new PanelFrame("Lookups by kind");
        frame.setSubtitle("a repeat asked what a lookup had already answered earlier in the "
            + "run, to this role or another");
        frame.setDense(true);
        frame.setContent(table);
        return frame;
    }

    private static Div lines(String text, String classes) {
        Div div = new Div(text);
        div.addClassName(classes);
        return div;
    }

    private static List<String> nonBlankLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text != null) {
            for (String line : text.split("\n")) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    /** Tab-separated rows, one per line, as the observer service sends them. */
    private static List<String[]> parseRows(String rows) {
        List<String[]> parsed = new ArrayList<>();
        for (String line : nonBlankLines(rows)) {
            parsed.add(line.split("\t"));
        }
        return parsed;
    }

    private static String cell(String[] row, int column) {
        return column < row.length ? row[column] : "";
    }

    private void refresh() {
        try {
            usage.set(observer.usageTable(runId));
            error.setVisible(false);
        } catch (Exception ex) {
            error.setText("The model calls per role could not be loaded. Press Refresh to try "
                + "again.");
            error.setVisible(true);
            ClientLog.error("UsagePanel", "the model calls per role failed to load - the "
                + "tables stay empty: " + ex);
        }
    }
}
