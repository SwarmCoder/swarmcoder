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

import java.util.ArrayList;
import java.util.List;

/**
 * Works out where a dependency line runs between two requirement boxes so that it never crosses a
 * third box.
 *
 * <p>The old drawing joined the two centres with a straight line, so two boxes in the same row with
 * a third between them had a line struck straight through the third one's title. This keeps the
 * straight line whenever it is clear, and otherwise finds the shortest way round: a shortest path
 * over the corners of the boxes in the way, each box grown by {@link #PAD} so the line keeps a
 * visible gap from the boxes it passes.
 *
 * <p>Pure arithmetic, no DOM. The cost is bounded: only boxes near the line are considered, and at
 * most {@link #MAX_OBSTACLES} of them.
 */
final class EdgeRouting {

    /** How far a line keeps from the side of a box it is not joined to. */
    static final double PAD = 14;
    /** At most this many boxes are considered for one line; any beyond are ignored. */
    private static final int MAX_OBSTACLES = 18;

    private EdgeRouting() {
    }

    /**
     * The points of the line from {@code from} to {@code to} (both inside their boxes), first and
     * last being those points. A straight line is two points.
     *
     * @param boxX   left edge of every box
     * @param boxY   top edge of every box
     * @param width  width of every box
     * @param height height of every box
     * @param skipA  index of one end's box, which does not obstruct
     * @param skipB  index of the other end's box
     */
    static double[][] route(double[] boxX, double[] boxY, double width, double height,
                            int skipA, int skipB, double[] from, double[] to) {
        double minX = Math.min(from[0], to[0]) - width;
        double maxX = Math.max(from[0], to[0]) + width;
        double minY = Math.min(from[1], to[1]) - height;
        double maxY = Math.max(from[1], to[1]) + height;
        double midX = (from[0] + to[0]) / 2;
        double midY = (from[1] + to[1]) / 2;
        // Obstacles as {left, top, right, bottom}, grown by PAD.
        List<double[]> obstacles = new ArrayList<>();
        for (int i = 0; i < boxX.length; i++) {
            if (i == skipA || i == skipB) {
                continue;
            }
            if (boxX[i] + width < minX || boxX[i] > maxX || boxY[i] + height < minY || boxY[i] > maxY) {
                continue;
            }
            obstacles.add(new double[]{boxX[i] - PAD, boxY[i] - PAD,
                boxX[i] + width + PAD, boxY[i] + height + PAD});
        }
        if (obstacles.size() > MAX_OBSTACLES) {
            // Keep the ones nearest the middle of the line, which are the ones most likely in the way.
            obstacles.sort((p, q) -> Double.compare(dist(p, midX, midY), dist(q, midX, midY)));
            obstacles = new ArrayList<>(obstacles.subList(0, MAX_OBSTACLES));
        }
        if (clear(from, to, obstacles)) {
            return new double[][]{from, to};
        }

        // Vertices: the two ends and every grown-box corner that is not itself inside another box.
        List<double[]> v = new ArrayList<>();
        v.add(from);
        v.add(to);
        for (double[] o : obstacles) {
            double[][] corners = {{o[0], o[1]}, {o[2], o[1]}, {o[2], o[3]}, {o[0], o[3]}};
            for (double[] c : corners) {
                if (!inside(c, obstacles)) {
                    v.add(c);
                }
            }
        }
        int n = v.size();
        double[] best = new double[n];
        int[] prev = new int[n];
        boolean[] done = new boolean[n];
        for (int i = 0; i < n; i++) {
            best[i] = Double.MAX_VALUE;
            prev[i] = -1;
        }
        best[0] = 0;
        for (int round = 0; round < n; round++) {
            int u = -1;
            for (int i = 0; i < n; i++) {
                if (!done[i] && best[i] < Double.MAX_VALUE && (u < 0 || best[i] < best[u])) {
                    u = i;
                }
            }
            if (u < 0 || u == 1) {
                break;
            }
            done[u] = true;
            for (int w = 0; w < n; w++) {
                if (done[w]) {
                    continue;
                }
                double d = Math.hypot(v.get(u)[0] - v.get(w)[0], v.get(u)[1] - v.get(w)[1]);
                // A small penalty per bend, so the line prefers fewer turns over a hair's-breadth
                // shorter way.
                double cost = best[u] + d + 6;
                if (cost < best[w] && clear(v.get(u), v.get(w), obstacles)) {
                    best[w] = cost;
                    prev[w] = u;
                }
            }
        }
        if (prev[1] < 0) {
            return new double[][]{from, to};   // boxed in: a straight line is better than none
        }
        List<double[]> path = new ArrayList<>();
        int at = 1;
        while (at >= 0) {
            path.add(0, v.get(at));
            at = prev[at];
        }
        return path.toArray(new double[0][]);
    }

    /**
     * Where a line leaves a box, going from {@code inside} (a point in the box) towards
     * {@code towards}. {@code margin} keeps the arrow head clear of the border.
     */
    static double[] exit(double left, double top, double width, double height,
                         double[] inside, double[] towards, double margin) {
        double dx = towards[0] - inside[0];
        double dy = towards[1] - inside[1];
        double tx = Double.MAX_VALUE;
        double ty = Double.MAX_VALUE;
        if (dx > 0) {
            tx = (left + width + margin - inside[0]) / dx;
        } else if (dx < 0) {
            tx = (left - margin - inside[0]) / dx;
        }
        if (dy > 0) {
            ty = (top + height + margin - inside[1]) / dy;
        } else if (dy < 0) {
            ty = (top - margin - inside[1]) / dy;
        }
        double t = Math.min(Math.min(tx, ty), 1);
        return new double[]{inside[0] + dx * t, inside[1] + dy * t};
    }

    private static double dist(double[] o, double x, double y) {
        return Math.hypot((o[0] + o[2]) / 2 - x, (o[1] + o[3]) / 2 - y);
    }

    private static boolean inside(double[] p, List<double[]> obstacles) {
        for (double[] o : obstacles) {
            if (p[0] > o[0] + 0.5 && p[0] < o[2] - 0.5 && p[1] > o[1] + 0.5 && p[1] < o[3] - 0.5) {
                return true;
            }
        }
        return false;
    }

    /** True when the segment {@code a}-{@code b} passes through the inside of none of the boxes. */
    private static boolean clear(double[] a, double[] b, List<double[]> obstacles) {
        for (double[] o : obstacles) {
            if (crosses(a, b, o[0] + 0.5, o[1] + 0.5, o[2] - 0.5, o[3] - 0.5)) {
                return false;
            }
        }
        return true;
    }

    /** Liang-Barsky: does the segment enter the rectangle's interior. */
    private static boolean crosses(double[] a, double[] b,
                                   double left, double top, double right, double bottom) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double t0 = 0;
        double t1 = 1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {a[0] - left, right - a[0], a[1] - top, bottom - a[1]};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] <= 0) {
                    return false;
                }
            } else {
                double r = q[i] / p[i];
                if (p[i] < 0) {
                    if (r > t1) {
                        return false;
                    }
                    t0 = Math.max(t0, r);
                } else {
                    if (r < t0) {
                        return false;
                    }
                    t1 = Math.min(t1, r);
                }
            }
        }
        return t0 < t1;
    }
}
