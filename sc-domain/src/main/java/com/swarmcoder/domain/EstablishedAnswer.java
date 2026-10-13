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

import java.util.HashMap;
import java.util.Map;

/**
 * One answer a project has established, or one thing a role concluded from such an answer, as it
 * is kept between starts of the application (stored in {@link ProjectEstablished}, schema v15;
 * section 101).
 *
 * <p>An answer is kept with what it stood on when it was computed: each file with the state it
 * had, the state of every root when the question can change with any file, and the reading of
 * the syntax tree when the tree was asked. It is served again only while all three are as
 * recorded; this class holds them and decides nothing.
 *
 * <p>Written as a fresh instance each time, never edited in place, so the store sees a new
 * instance and writes it. A mutable POJO because EclipseStore cannot persist records.
 */
public class EstablishedAnswer {
    /** The question: who it is written for, the query and its argument. Null for a finding. */
    private String question;
    /** The answer's text; for a finding, the role's sentence. */
    private String text;
    /** A word of the lookup's own about the answer; may be null. */
    private String tag;
    /** Who asked first; for a finding, the role that concluded it. */
    private String by;
    /** A finding only: the type or file it is about; may be null. */
    private String about;
    /** A finding only: the lookup it comes from, as tool, a space, its argument. */
    private String lookup;
    /** Each file the answer stood on, by its absolute path, with the state it had. */
    private Map<String, String> files = new HashMap<>();
    /** The state of every root, or null when the files are all it stood on. */
    private String roots;
    /** The reading of the syntax tree it was answered from, or null when the tree was not asked. */
    private String tree;
    private long keptAtMillis;

    public EstablishedAnswer() {}

    public EstablishedAnswer(String question, String text, String tag, String by, String about,
                             String lookup, Map<String, String> files, String roots, String tree,
                             long keptAtMillis) {
        this.question = question;
        this.text = text;
        this.tag = tag;
        this.by = by;
        this.about = about;
        this.lookup = lookup;
        this.files = files == null ? new HashMap<>() : new HashMap<>(files);
        this.roots = roots;
        this.tree = tree;
        this.keptAtMillis = keptAtMillis;
    }

    public String question() { return question; }
    public String text() { return text; }
    public String tag() { return tag; }
    public String by() { return by; }
    public String about() { return about; }
    public String lookup() { return lookup; }
    public String roots() { return roots; }
    public String tree() { return tree; }
    public long keptAtMillis() { return keptAtMillis; }

    public Map<String, String> files() {
        return files == null ? Map.of() : files;
    }
}
