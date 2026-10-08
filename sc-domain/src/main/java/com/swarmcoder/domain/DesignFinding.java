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
package com.swarmcoder.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One thing the architect established about how this project or its framework does something,
 * kept for the workers while it designed (owner's decision, 2026-10-08; section 73).
 *
 * <p>The architect runs on the strong model and looks the project up before it designs. Until
 * this existed nothing of that reached a worker: the task planner looked the same things up a
 * second time and wrote them into each task as prose, and the worker - the weakest model of the
 * run - started from that prose. A finding is the architect's own lookup, handed on as it was
 * found: what it is about, where it came from, one sentence, and where there is code, the lines
 * the lookup returned. The lines are copied by the tool from the lookup's result; no model
 * re-types them.
 *
 * <p>This is not the prompt-stuffing CLAUDE.md section 1 forbids. Nothing here is pasted to save
 * a role a lookup it has not made: every finding was selected by a role with judgement, for this
 * design, names its source, and is bounded when it is recorded and again per task.
 *
 * <p>A plain class with setters, like {@link ApiContract}: it is persisted with the design and
 * with the tasks it travels with.
 */
public class DesignFinding {
    private UUID id;
    /**
     * What the finding is about: a contract's name or the name of a type to build or change,
     * as the architect wrote it. Null or blank is the project as a whole.
     */
    private String about;
    /** The lookup the finding came from, as it was made: the tool and its argument. */
    private String source;
    /** The fact, in the architect's sentence. */
    private String note;
    /** Lines of the lookup's result, verbatim; null or blank when the sentence is the fact. */
    private String snippet;

    public DesignFinding() {}

    public DesignFinding(UUID id, String about, String source, String note, String snippet) {
        this.id = id;
        this.about = about;
        this.source = source;
        this.note = note;
        this.snippet = snippet;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String about() { return about; }
    public String getAbout() { return about; }
    public void setAbout(String about) { this.about = about; }
    public String source() { return source; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String note() { return note; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String snippet() { return snippet; }
    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }

    /** True when the finding is about the project as a whole and no one type. */
    public boolean wholeProject() {
        return about == null || about.isBlank();
    }

    public boolean hasSnippet() {
        return snippet != null && !snippet.isBlank();
    }

    /**
     * True when the finding is about this contract: {@link #about} is the contract's name, its
     * fully-qualified type name or its simple type name, compared without case. The same three
     * names a planned task may name a contract by.
     */
    public boolean isAbout(ApiContract contract) {
        if (contract == null || wholeProject()) {
            return false;
        }
        String wanted = about.strip();
        return wanted.equalsIgnoreCase(contract.name())
            || (contract.namesAType() && (wanted.equalsIgnoreCase(contract.typeName().strip())
                || wanted.equalsIgnoreCase(contract.simpleTypeName())));
    }

    /** The simple name of what the finding is about, or "" for the whole project. */
    public String aboutSimpleName() {
        if (wholeProject()) {
            return "";
        }
        String name = about.strip();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /** The same finding without its code: the sentence and where to read the rest. */
    public DesignFinding withoutSnippet() {
        return new DesignFinding(id, about, source, note, null);
    }

    /** How many characters {@link #render()} takes. */
    public int size() {
        return render().length();
    }

    /** The finding as a role is shown it: what about, the sentence, the source, the lines. */
    public String render() {
        StringBuilder sb = new StringBuilder("- [")
            .append(wholeProject() ? "the whole project" : about.strip()).append("] ")
            .append(note == null ? "" : note.strip());
        if (source != null && !source.isBlank()) {
            sb.append(" (from ").append(source.strip()).append(')');
        }
        sb.append('\n');
        if (hasSnippet()) {
            for (String line : snippet.split("\\R", -1)) {
                sb.append("    ").append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** What stands above the findings a worker or a test author is given with a task. */
    public static final String HEADING = "WHAT THE ARCHITECT ESTABLISHED FOR THIS TASK - how "
        + "this project and its framework do it. Each line is a fact the architect looked up "
        + "while designing, with the lookup it came from and, indented under it, the lines that "
        + "lookup returned, as they are. Build on these first; the lookup named reads more of "
        + "the same place.\n";

    /** The findings under {@link #HEADING}, or "" when there are none. */
    public static String renderAll(List<DesignFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(HEADING);
        for (DesignFinding finding : findings) {
            if (finding != null) {
                sb.append(finding.render());
            }
        }
        return sb.toString();
    }

    /** The characters {@link #renderAll} takes for these findings, the heading not counted. */
    public static int sizeOf(List<DesignFinding> findings) {
        int size = 0;
        for (DesignFinding finding : findings == null ? List.<DesignFinding>of() : findings) {
            size += finding == null ? 0 : finding.size();
        }
        return size;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DesignFinding that = (DesignFinding) o;
        return Objects.equals(id, that.id) && Objects.equals(about, that.about)
            && Objects.equals(source, that.source) && Objects.equals(note, that.note)
            && Objects.equals(snippet, that.snippet);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, about, source, note, snippet);
    }
}
