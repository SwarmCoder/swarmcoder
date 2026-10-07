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

import com.swarmcoder.console.api.RoleEntryDto;
import com.zeroz4j.ui.theme.TextStyle;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.component.Select;
import com.zeroz4j.ui.component.TextField;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * Editable per-role endpoint rows (design §7): baseUrl / model / key / thinking per role,
 * plus add/remove for worker families. Used by both the global settings dialog and the
 * per-project overrides dialog ({@code placeholderHint} says what blank means there).
 *
 * <p>Each row also folds open into that model's own settings — the generation cap, how reasoning
 * is switched off, whether the chat template takes native tool-call history, the HTTP version, the
 * two context sizes and the two figures that decide how many workers are admitted at once. They
 * are collapsed by default because the normal case is picking a starting shape and changing
 * nothing, and every one of them falls back to the shape when left blank.
 */
final class RoleForm extends Div {

    /** Tri-state option labels, in the order matching the DTO's {@code -1 / 0 / 1}. */
    private static final List<String> TOOL_HISTORY =
        List.of("tool history: from shape", "tool history: native", "tool history: plain JSON text");
    private static final List<String> JSON_FORMAT =
        List.of("JSON format: from shape", "JSON format: not honoured", "JSON format: honoured");
    private static final List<String> MEASURED =
        List.of("measured: from shape", "measured: NO — unverified", "measured: yes, on this box");
    private static final List<String> HTTP_VERSIONS =
        List.of("HTTP: from shape", "HTTP/1.1", "HTTP/2");
    private static final String SHAPE_UNSET = "shape: none picked";

    private final class Row {
        final String roleId;
        final TextField baseUrl = new TextField();
        final TextField model = new TextField();
        final TextField apiKey = new TextField();
        final Select thinking = new Select();
        final Div element;

        // --- the model's own behaviour, folded away until "model settings" is clicked ----------
        final Select shape = new Select();
        final TextField maxOutputTokens = new TextField();
        final TextField thinkingKwarg = new TextField();
        final TextField noThinkDirective = new TextField();
        final Select toolHistory = new Select();
        final Select jsonFormat = new Select();
        final Select httpVersion = new Select();
        final TextField servedContext = new TextField();
        final TextField workingContext = new TextField();
        final TextField kvBytesPerToken = new TextField();
        final TextField maxConcurrentSequences = new TextField();
        final Select measured = new Select();

        Row(RoleEntryDto entry, boolean removable) {
            this.roleId = entry.getRoleId();
            element = new Div();
            element.addClassName("flex flex-col gap-1.5");

            Div line = new Div();
            line.addClassName("grid grid-cols-[7rem_1fr_10rem_10rem_6rem_auto_auto] gap-2 items-center");
            Span label = new Span(entry.getRoleId());
            label.addClassName("text-xs font-mono text-base-content/60 truncate");
            line.getElement().appendChild(label.getElement());

            style(baseUrl, entry.getBaseUrl(), "base URL" + placeholderHint);
            style(model, entry.getModelName(), "model" + placeholderHint);
            style(apiKey, entry.getApiKey(), "API key");
            apiKey.getElement().setAttribute("type", "password");
            line.add(baseUrl, model, apiKey);

            thinking.addClassName("select select-bordered select-xs");
            thinking.setItems(List.of("thinking: default", "thinking: off", "thinking: on"));
            thinking.setValue(entry.getThinking() == 1 ? "thinking: on"
                : entry.getThinking() == 0 ? "thinking: off" : "thinking: default");
            line.add(thinking);

            Div advanced = advancedPanel(entry);
            advanced.setVisible(false);
            boolean[] open = {false};

            Div toggle = new Div();
            toggle.addClassName("cursor-pointer text-xs text-base-content/50 hover:text-primary "
                + "whitespace-nowrap");
            toggle.setText("model settings");
            toggle.getElement().setAttribute("title",
                "How this model is spoken to: output cap, reasoning switch, tool-call history "
                    + "format, HTTP version, context sizes, and the figures that decide how many "
                    + "workers run at once");
            toggle.addDomEventListener("click", e -> {
                open[0] = !open[0];
                advanced.setVisible(open[0]);
            });
            line.add(toggle);

            if (removable) {
                Div remove = new Div();
                remove.addClassName("cursor-pointer text-base-content/40 hover:text-error");
                remove.add(Icon.of("x", "w-3.5 h-3.5"));
                remove.getElement().setAttribute("title", "Remove worker family");
                remove.addDomEventListener("click", e -> {
                    rows.remove(this);
                    rowsHost.remove(element);
                });
                line.add(remove);
            } else {
                line.add(new Div());
            }

            element.add(line, advanced);
        }

        /**
         * The model's own settings. Everything here falls back to the picked shape when left
         * blank, so an operator fills in only what they actually know — the alternative, a wall of
         * blank fields with no starting point, is how wrong numbers get invented.
         */
        private Div advancedPanel(RoleEntryDto entry) {
            Div panel = new Div();
            panel.addClassName("ml-[7.5rem] mb-2 rounded-md border border-base-300 bg-base-200/40 p-2 "
                + "flex flex-col gap-2");

            List<String> shapes = new ArrayList<>();
            shapes.add(SHAPE_UNSET);
            if (entry.getAvailableShapes() != null && !entry.getAvailableShapes().isBlank()) {
                for (String id : entry.getAvailableShapes().split(",")) {
                    if (!id.strip().isEmpty()) {
                        shapes.add(id.strip());
                    }
                }
            }
            shape.addClassName("select select-bordered select-xs");
            shape.setItems(shapes);
            shape.setValue(shapes.contains(entry.getShape()) ? entry.getShape() : SHAPE_UNSET);

            Div shapeLine = new Div();
            shapeLine.addClassName("flex items-center gap-2 flex-wrap");
            Span shapeLabel = new Span("Start from a known model shape, then change what differs:");
            TextStyle.CAPTION.applyTo(shapeLabel);
            shapeLine.getElement().appendChild(shapeLabel.getElement());
            shapeLine.add(shape);
            panel.add(shapeLine);

            Div grid = new Div();
            grid.addClassName("grid grid-cols-[minmax(11rem,1fr)_minmax(11rem,1fr)_minmax(11rem,1fr)] "
                + "gap-x-3 gap-y-2");
            grid.add(
                field("Longest answer, tokens", number(maxOutputTokens, entry.getMaxOutputTokens()),
                    "Stops one runaway answer from filling the whole context and stalling the step."),
                field("Turn thinking off with this setting name",
                    text(thinkingKwarg, entry.getThinkingKwarg(), "e.g. enable_thinking, or none"),
                    "Sent to the server as a chat-template argument. Type none if this model has no "
                        + "such setting — sending one it does not know can make it fail."),
                field("Turn thinking off with this text",
                    text(noThinkDirective, entry.getNoThinkDirective(), "e.g. /no_think, or none"),
                    "Added to the end of the instructions the model is given. Used for the workers, "
                        + "where the chat-template argument above cannot be sent. Type none if this "
                        + "model has no such text."),
                field("Past tool calls sent as",
                    tri(toolHistory, TOOL_HISTORY, entry.getTextualToolHistory()),
                    "Some models' templates crash on the second turn unless past tool calls are "
                        + "written out as plain text. Plain text is the safe answer."),
                field("Asking for JSON back",
                    tri(jsonFormat, JSON_FORMAT, entry.getJsonResponseFormat()),
                    "Whether this server accepts a plain \"reply in JSON\" instruction, or needs the "
                        + "exact shape sent with the request instead."),
                field("Connection type",
                    tri(httpVersion, HTTP_VERSIONS, httpIndex(entry.getHttpVersion())),
                    "Leave at HTTP/1.1 unless you know the server is fine with HTTP/2 — one server "
                        + "build received empty requests over HTTP/2."),
                field("Context the server was started with",
                    number(servedContext, entry.getServedContextTokens()),
                    "Must match how the model server was actually started. This is headroom, not a "
                        + "target."),
                field("Context one job may use",
                    number(workingContext, entry.getWorkingContextTokens()),
                    "Kept below the number on the left on purpose, so a job never runs up against "
                        + "the server's limit."),
                field("Memory per token, bytes",
                    number(kvBytesPerToken, entry.getKvBytesPerToken()),
                    "MEASURE THIS. It decides how many jobs are let in at once. It cannot be worked "
                        + "out from the model's size, and a mixture-of-experts model does not behave "
                        + "like an ordinary one."),
                field("Requests served at once",
                    number(maxConcurrentSequences, entry.getMaxConcurrentSequences()),
                    "MEASURE THIS. It is the ceiling on how many workers can attack one task at the "
                        + "same time."),
                field("Have these been measured?",
                    tri(measured, MEASURED, entry.getVerified()),
                    "Say yes only once somebody has measured the two numbers above against THIS "
                        + "model on THIS machine. Until then the startup log warns, which is what "
                        + "you want."));
            panel.add(grid);
            return panel;
        }

        private void style(TextField field, String value, String placeholder) {
            field.addClassName("input input-bordered input-xs font-mono");
            field.setValue(value == null ? "" : value);
            field.getElement().setAttribute("placeholder", placeholder);
        }

        RoleEntryDto read() {
            RoleEntryDto dto = new RoleEntryDto();
            dto.setRoleId(roleId);
            dto.setBaseUrl(value(baseUrl));
            dto.setModelName(value(model));
            dto.setApiKey(value(apiKey));
            String think = thinking.getValue();
            dto.setThinking("thinking: on".equals(think) ? 1
                : "thinking: off".equals(think) ? 0 : -1);
            dto.setShape(SHAPE_UNSET.equals(shape.getValue()) ? "" : shape.getValue());
            dto.setMaxOutputTokens(intValue(maxOutputTokens));
            dto.setThinkingKwarg(value(thinkingKwarg));
            dto.setNoThinkDirective(value(noThinkDirective));
            dto.setTextualToolHistory(triValue(toolHistory, TOOL_HISTORY));
            dto.setJsonResponseFormat(triValue(jsonFormat, JSON_FORMAT));
            int http = triValue(httpVersion, HTTP_VERSIONS);
            dto.setHttpVersion(http == 0 ? "1.1" : http == 1 ? "2" : "");
            dto.setServedContextTokens(intValue(servedContext));
            dto.setWorkingContextTokens(intValue(workingContext));
            dto.setKvBytesPerToken(intValue(kvBytesPerToken));
            dto.setMaxConcurrentSequences(intValue(maxConcurrentSequences));
            dto.setVerified(triValue(measured, MEASURED));
            return dto;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Div rowsHost = new Div();
    private final String placeholderHint;
    /** The shape catalogue the server sent, carried onto rows the operator adds here. */
    private final String availableShapes;
    private int nextWorkerIndex;

    RoleForm(List<RoleEntryDto> entries, boolean overrides) {
        this.placeholderHint = overrides ? " (blank = inherit global)" : "";
        String shapes = "";
        for (RoleEntryDto entry : entries) {
            if (entry.getAvailableShapes() != null && !entry.getAvailableShapes().isBlank()) {
                shapes = entry.getAvailableShapes();
                break;
            }
        }
        this.availableShapes = shapes;
        addClassName("flex flex-col gap-1.5");
        rowsHost.addClassName("flex flex-col gap-1.5");
        int workerCount = 0;
        for (RoleEntryDto entry : entries) {
            if (entry.getRoleId() != null && entry.getRoleId().startsWith("worker")
                    && entry.getBaseUrl() != null && !entry.getBaseUrl().isBlank()) {
                workerCount++;
            }
        }
        if (!overrides) {
            add(diversityNotice(workerCount));
        }
        add(rowsHost);
        for (RoleEntryDto entry : entries) {
            boolean worker = entry.getRoleId() != null && entry.getRoleId().startsWith("worker");
            if (worker) {
                nextWorkerIndex++;
            }
            Row row = new Row(entry, worker);
            rows.add(row);
            rowsHost.add(row.element);
        }
        Div addWorker = new Div();
        addWorker.addClassName("flex items-center gap-1.5 text-xs text-base-content/50 "
            + "hover:text-primary cursor-pointer mt-1 w-fit");
        addWorker.add(Icon.of("plus", "w-3.5 h-3.5"));
        Span label = new Span("add worker family");
        addWorker.getElement().appendChild(label.getElement());
        addWorker.addDomEventListener("click", e -> {
            RoleEntryDto blank = new RoleEntryDto();
            blank.setRoleId("worker" + nextWorkerIndex++);
            blank.setBaseUrl("");
            blank.setModelName("");
            blank.setApiKey("");
            blank.setAvailableShapes(availableShapes);
            Row row = new Row(blank, true);
            rows.add(row);
            rowsHost.add(row.element);
        });
        add(addWorker);
    }

    /**
     * Says what diversity a swarm actually has. The design assumed two models side by side, so
     * that ten workers on one task would disagree usefully; when only one is configured, the
     * "split across families" setting has nothing to split across and does nothing. That used to
     * be true silently, while the settings still read as though model diversity was on.
     */
    private static Div diversityNotice(int workerCount) {
        Div notice = new Div();
        notice.addClassName("rounded-md border border-base-300 bg-base-200/40 px-2 py-1.5 mb-2 "
            + "text-xs text-base-content/70 leading-relaxed");
        if (workerCount == 0) {
            notice.addClassName("border-error/40");
            notice.setText("No worker model is set up yet. Workers cannot run until one has a base "
                + "URL and a model name.");
        } else if (workerCount == 1) {
            notice.setText("One worker model is set up, so every worker in a swarm runs the SAME "
                + "model. The \"split across model families\" setting has nothing to split across "
                + "and does nothing. What still makes the workers differ: each gets a different "
                + "temperature, and each is asked for something different (smallest change, "
                + "defensive edges, literal to the tests, willing to tidy up).");
        } else {
            notice.setText(workerCount + " worker models are set up. If \"split across model "
                + "families\" is on, workers alternate between the first two.");
        }
        return notice;
    }

    List<RoleEntryDto> read() {
        List<RoleEntryDto> result = new ArrayList<>();
        for (Row row : rows) {
            result.add(row.read());
        }
        return result;
    }

    // --- small field helpers, so the panel above reads as a list of settings ---------------------

    private static Div field(String label, com.zeroz4j.ui.component.Component input, String help) {
        Div wrap = new Div();
        wrap.addClassName("flex flex-col gap-0.5");
        Span name = new Span(label);
        name.addClassName("text-[0.7rem] text-base-content/70");
        wrap.getElement().appendChild(name.getElement());
        wrap.add(input);
        Span hint = new Span(help);
        hint.addClassName("text-[0.65rem] text-base-content/45 leading-snug");
        wrap.getElement().appendChild(hint.getElement());
        return wrap;
    }

    private static TextField number(TextField field, int value) {
        field.addClassName("input input-bordered input-xs font-mono w-full");
        field.setValue(value > 0 ? String.valueOf(value) : "");
        field.getElement().setAttribute("placeholder", "from shape");
        return field;
    }

    private static TextField text(TextField field, String value, String placeholder) {
        field.addClassName("input input-bordered input-xs font-mono w-full");
        field.setValue(value == null ? "" : value);
        field.getElement().setAttribute("placeholder", placeholder);
        return field;
    }

    private static Select tri(Select select, List<String> options, int value) {
        select.addClassName("select select-bordered select-xs w-full");
        select.setItems(options);
        int index = value < 0 || value >= options.size() ? 0 : value + 1;
        select.setValue(options.get(Math.min(index, options.size() - 1)));
        return select;
    }

    private static int triValue(Select select, List<String> options) {
        int index = options.indexOf(select.getValue());
        return index <= 0 ? -1 : index - 1;
    }

    /** "1.1" and "2" map onto the tri-state's 0/1 slots; blank inherits. */
    private static int httpIndex(String httpVersion) {
        if (httpVersion == null || httpVersion.isBlank()) {
            return -1;
        }
        return httpVersion.trim().startsWith("2") ? 1 : 0;
    }

    private static String value(TextField field) {
        String v = field.getValue();
        return v == null ? "" : v.strip();
    }

    private static int intValue(TextField field) {
        String v = value(field);
        if (v.isEmpty()) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(v);
            return parsed > 0 ? parsed : 0;
        } catch (NumberFormatException e) {
            // A typo must not become a number the scheduler then trusts; blank means "from shape".
            return 0;
        }
    }
}
