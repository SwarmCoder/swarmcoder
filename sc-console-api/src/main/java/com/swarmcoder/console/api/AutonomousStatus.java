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
package com.swarmcoder.console.api;

import com.zeroz4j.api.DataModel;

import java.util.Objects;

/**
 * Whether the machine is deciding things on the operator's behalf, and which gate it is at.
 *
 * <p>It exists because autonomous running was only ever visible inside the window that armed it.
 * Closing that window did not stop it — nothing in the pilot has ever cared whether a browser was
 * looking — but it did remove every trace of it from the screen, so the operator's only way to
 * know it was still on was to reopen the window and read a status line that a timer refreshed
 * every four seconds. A person who cannot tell a working run from a hung one asks "is it stuck?",
 * and the honest answer has to be on screen wherever they happen to be standing.
 *
 * <h2>Two shapes of the same sentence, and why both</h2>
 *
 * <p>{@link #line()} is the whole of it: "Running on its own. 1 building, 3 waiting to start. 18
 * minutes so far, 35160 tokens spent, stops by 19:02 UTC at the latest." The operator's verdict on
 * that sentence was that it is the good part of the feature — what is happening, how long, what it
 * has cost, when it ends by itself — and it was only ever in the wrong place. It is what the strip
 * across the top of every screen shows, word for word.
 *
 * <p>{@link #activity()} is the gate alone, in three or four words — "Reading your documents",
 * "Agreeing what it found", "2 building, 1 waiting to start". It is the same fact at a size that
 * fits somewhere narrow, and it is what a caller compares against when it needs the gate rather
 * than the prose.
 *
 * <p>Both are written by {@code AutonomousMode}, in one method, from one session. The browser never
 * assembles a version of its own — that is how "building now" here and "stopped" there happened
 * once already.
 *
 * <h2>What is deliberately NOT here</h2>
 *
 * <p>No count of decisions taken and none of the ones it invented. Those are the record, they are
 * read in the window that holds them, and a number on permanent display would be a second
 * derivation of a list that window already counts for itself. Nothing that only the record can
 * answer belongs on a strip nobody can act from.
 *
 * <p>No stories, no workers, no run health. All of those are already live on the board and in the
 * run graph, and they look the same whether a person or the machine started the work. The only
 * facts here are the ones a manual run does not have.
 */
@DataModel
public class AutonomousStatus {

    /** True while the machine is entitled to decide things on the operator's behalf. */
    private boolean running;
    /** The gate it is at, in three or four words. Blank when nothing is running. */
    private String activity;
    /** The whole sentence: the gate, how long, what it has spent, and when it stops. */
    private String line;
    /**
     * The name of the project it is driving. Blank when nothing is running.
     *
     * <p>Here because a session is bound to the project it was started on and will not touch
     * another: switch project and the pilot goes quiet, correctly, while the switch is still on. A
     * mark that went on saying "building on its own" over a project where nothing is happening
     * would be worse than no mark, so the name travels with the fact and the screen compares it
     * with the project the operator has open.
     */
    private String project;

    public AutonomousStatus() {}

    public AutonomousStatus(boolean running, String activity, String line, String project) {
        this.running = running;
        this.activity = activity;
        this.line = line;
        this.project = project;
    }

    /** Nothing is being decided for anybody — and the signal's initial value. */
    public static AutonomousStatus off() {
        return new AutonomousStatus(false, "",
            "Not running. Nothing is being decided for you.", "");
    }

    public boolean running() { return running; }
    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }

    public String activity() { return activity == null ? "" : activity; }
    public String getActivity() { return activity; }
    public void setActivity(String activity) { this.activity = activity; }

    public String line() { return line == null ? "" : line; }
    public String getLine() { return line; }
    public void setLine(String line) { this.line = line; }

    public String project() { return project == null ? "" : project; }
    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }

    /**
     * Value equality, because the signal dedups by it: a tick that changed nothing must not
     * republish, and a tick that moved the clock on must.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AutonomousStatus that)) {
            return false;
        }
        return running == that.running
            && Objects.equals(activity(), that.activity())
            && Objects.equals(line(), that.line())
            && Objects.equals(project(), that.project());
    }

    @Override
    public int hashCode() {
        return Objects.hash(running, activity(), line(), project());
    }
}
