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

import com.swarmcoder.console.api.HealthDto;
import com.swarmcoder.console.api.HealthService;
import com.swarmcoder.console.api.HealthService_Stub;
import com.zeroz4j.ui.component.Icon;
import com.zeroz4j.ui.layout.Div;
import com.zeroz4j.ui.layout.Span;
import com.zeroz4j.signals.Effect;
import com.zeroz4j.signals.ValueSignal;
import org.teavm.jso.browser.Window;

/**
 * The live health strip in the status bar (design §8): Spark reachability, Docker sandbox,
 * in-flight workers/runs, pending approvals, and cloud-budget burn. Polls {@link HealthService}
 * every few seconds — the one place in the shell that pulls rather than waiting on a push.
 */
final class HealthStrip extends Div {

    private final HealthService health = new HealthService_Stub();
    private final ValueSignal<HealthDto> snapshot = new ValueSignal<>(null);
    /**
     * Whether the strip is currently showing values it can no longer refresh. A signal rather than
     * a plain flag so {@link #render} re-runs on the change — the chips must stop looking live the
     * moment they stop being live.
     */
    private final ValueSignal<Boolean> stale = new ValueSignal<>(false);
    /**
     * The failure state already reported, so only the edges are logged. This polls every five
     * seconds; a line per tick would push every other message out of the log while adding nothing
     * the first line did not already say, which makes it worse than silence.
     */
    private boolean pollFailing;

    HealthStrip() {
        addClassName("flex items-center gap-3");
        Effect.create(this::render);
        poll();
    }

    private void render() {
        removeAll();
        HealthDto h = snapshot.get();
        // Read unconditionally, before the early return, so the effect depends on it either way.
        boolean showStale = Boolean.TRUE.equals(stale.get());
        if (h == null) {
            // No snapshot has ever arrived. "connecting…" is honest while the first poll is still
            // in flight and a lie once polling is failing — left as-is it sits there forever
            // looking like a slow start.
            Span connecting = new Span(showStale ? "health unavailable" : "connecting…");
            connecting.addClassName(showStale ? "text-error" : "text-base-content/40");
            if (showStale) {
                connecting.getElement().setAttribute("title",
                    "the health poll is failing — no health data has been received");
            }
            getElement().appendChild(connecting.getElement());
            return;
        }
        add(chip(dotColor(h.getSparkStatus()), "Spark", sparkTooltip(h)));
        add(chip("on".equals(h.getDockerStatus()) ? "bg-success" : "bg-base-content/30",
            "Docker", h.getDockerStatus()));
        add(metric("bolt", h.getActiveWorkers() + "w"));
        add(metric("graph", h.getActiveRuns() + "/" + h.getTotalRuns() + " runs"));
        if (h.getPendingApprovals() > 0) {
            Span approvals = new Span(h.getPendingApprovals() + " to approve");
            approvals.addClassName("text-warning font-semibold");
            getElement().appendChild(approvals.getElement());
        }
        add(metric("heart", budgetLabel(h)));
        if (showStale) {
            // The chips above are frozen at the last successful poll and cannot change again until
            // it recovers. Unmarked they read as current, which is the whole failure: a Docker dot
            // that went green an hour ago is worse than no dot at all.
            Span staleMark = new Span("stale");
            staleMark.addClassName("text-error font-semibold");
            staleMark.getElement().setAttribute("title",
                "the health poll is failing — these values are from the last successful check");
            getElement().appendChild(staleMark.getElement());
        }
    }

    private Div chip(String dotColor, String label, String title) {
        Div chip = new Div();
        chip.addClassName("flex items-center gap-1");
        // The tooltip may be several lines; a native title attribute renders newlines, which is
        // why this is not a styled popover — it must survive the strip being clipped at the window
        // edge, and a hand-rolled one would not.
        chip.getElement().setAttribute("title", label + " — " + title);
        Span dot = new Span();
        dot.addClassName("inline-block w-2 h-2 rounded-full " + dotColor);
        Span text = new Span(label);
        chip.getElement().appendChild(dot.getElement());
        chip.getElement().appendChild(text.getElement());
        return chip;
    }

    /**
     * Everything known about the worker endpoint, for the hover.
     *
     * <p>"Spark: down" was true and useless. It never said WHICH endpoint was measured, so a healthy
     * server on a different port read identically to a dead one; and when up it never said what was
     * actually loaded, so a box serving the wrong model looked perfectly well. The probe already had
     * all of this and was throwing it away.
     */
    private static String sparkTooltip(HealthDto h) {
        StringBuilder out = new StringBuilder();
        String status = h.getSparkStatus() == null ? "unknown" : h.getSparkStatus();
        out.append(switch (status) {
            case "up" -> "Reachable";
            case "down" -> "Not answering";
            default -> "Not checked";
        });
        if (h.getSparkUrl() != null && !h.getSparkUrl().isBlank()) {
            out.append('\n').append(h.getSparkUrl());
        }
        if (h.getSparkModels() != null && !h.getSparkModels().isBlank()) {
            out.append("\nServing: ").append(h.getSparkModels());
        } else if ("up".equals(status)) {
            out.append("\nServing: the endpoint answered but listed no models");
        }
        if (h.getSparkDetail() != null && !h.getSparkDetail().isBlank()) {
            out.append('\n').append(h.getSparkDetail());
        }
        // The endpoint is whichever worker family is configured first, and saying so is the
        // difference between "my server is broken" and "you are probing a different box".
        out.append("\n\nThis is the first entry in roles.workerFamilies. Re-checked every few "
            + "seconds — a server that comes back turns this green on its own.");
        return out.toString();
    }

    private Div metric(String icon, String text) {
        Div metric = new Div();
        metric.addClassName("flex items-center gap-1");
        metric.add(Icon.of(icon, "w-3 h-3 opacity-50"));
        Span label = new Span(text);
        metric.getElement().appendChild(label.getElement());
        return metric;
    }

    private static String budgetLabel(HealthDto h) {
        if (h.getBudgetMax() > 0) {
            long pct = Math.round(100.0 * h.getBudgetUsed() / h.getBudgetMax());
            return compact(h.getBudgetUsed()) + "/" + compact(h.getBudgetMax()) + " (" + pct + "%)";
        }
        return compact(h.getBudgetUsed()) + " tok";
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

    private static String dotColor(String status) {
        return switch (status == null ? "" : status) {
            case "up" -> "bg-success";
            case "down" -> "bg-error";
            default -> "bg-base-content/30";
        };
    }

    /**
     * Reloads the health snapshot every five seconds.
     *
     * <p>The RMI call runs on a TeaVM green thread because BOTH callers are non-threading contexts:
     * the constructor (reached from the client bootstrap) and a {@code Window.setTimeout} callback.
     * A suspending call made directly from either throws "Suspension point reached from
     * non-threading context" — which is exactly what this was doing, on every tick since it was
     * written, while an empty catch kept it quiet and the strip read "connecting…" forever.
     *
     * <p>The timeout is scheduled OUTSIDE the thread so a hung call cannot stop the next tick, and
     * so the schedule survives whatever the call does.
     */
    private void poll() {
        new Thread(() -> {
            try {
                snapshot.set(health.snapshot());
                if (pollFailing) {
                    // The recovery edge matters as much as the failure edge: without it the log
                    // says the strip broke and never says it came back, so every later reader has
                    // to guess whether the values they are looking at are trustworthy.
                    pollFailing = false;
                    stale.set(false);
                    ClientLog.warn("HealthStrip",
                        "health poll recovered — the strip is showing live values again");
                }
            } catch (Exception e) {
                // Only the transition is reported; pollFailing keeps the next tick quiet. The last
                // snapshot is still on screen and there is no way to refresh it, so the strip has
                // to admit that rather than keep presenting old numbers as current.
                if (!pollFailing) {
                    pollFailing = true;
                    stale.set(true);
                    ClientLog.error("HealthStrip", "health poll failed — the strip is showing "
                        + "stale values and will not update until it recovers: " + e);
                }
            }
        }).start();
        Window.setTimeout(this::poll, 5000);
    }
}

