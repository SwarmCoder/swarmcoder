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
package com.swarmcoder.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * What is KEPT out of a parsed program, and the file it is kept in.
 *
 * <p><b>Why relations and not the tree itself.</b> An OpenRewrite Lossless Semantic Tree is the
 * whole program with every space and comment in it, and re-deriving one costs a compile. The
 * obvious thing to do is to serialise the tree and read it back, and that is exactly what
 * OpenRewrite's Apache-2.0 library cannot do: its own documentation says "Nothing is stored
 * between recipe runs" and "the LST must fit into memory", and its README names serialised,
 * reusable LSTs as what the commercial Moderne platform adds on top
 * (docs.openrewrite.org/concepts-and-explanations/lossless-semantic-trees, and the README of
 * github.com/openrewrite/rewrite). {@code RewriteRpc} in the open library is a live wire protocol
 * to a running peer, not a file format, and {@code TypeTable} caches classpath SIGNATURES, not
 * parsed sources.
 *
 * <p>So the tree is built once, every relation a query needs is read out of it, and only those
 * relations are written to disk. The result is a few megabytes instead of a few hundred, it loads
 * in milliseconds instead of a compile, and nothing in it can go stale independently of the
 * fingerprint it is keyed by.
 *
 * <p><b>Everything here is data read out of somebody else's source code.</b> It is quoted back to
 * a worker with the file and line it came from; it is never executed and never treated as an
 * instruction.
 */
final class SemanticFacts {

    private static final Logger log = LoggerFactory.getLogger(SemanticFacts.class);

    /**
     * Bumped whenever the shape of what is extracted changes, so a cache written by an older
     * build is ignored rather than half-understood. It is part of the cache key, exactly as
     * {@code INDEX_FORMAT} is for the text index.
     */
    static final String FORMAT = "lst-relations-v3";

    private static final ObjectMapper JSON = new ObjectMapper();

    private SemanticFacts() {}

    /** One type declared under a root, with what it is and what it is assignable to. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TypeFact(String fqn, String file, int line, String kind, boolean isAbstract,
                    List<String> assignableTo, List<String> annotations, List<String> shape) {

        TypeFact {
            assignableTo = assignableTo == null ? List.of() : List.copyOf(assignableTo);
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
            shape = shape == null ? List.of() : List.copyOf(shape);
        }

        String simpleName() {
            int dot = fqn.lastIndexOf('.');
            return dot < 0 ? fqn : fqn.substring(dot + 1);
        }
    }

    /** One place a resolved type is named, with the line it is named on. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UseFact(String fqn, String file, int line, String kind) {}

    /** One call, resolved: whose method, called from where. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CallFact(String declaringType, String method, String file, int line,
                    String enclosingType, String enclosingMethod) {

        String target() {
            return declaringType + "#" + method;
        }

        String caller() {
            return enclosingType + "#" + enclosingMethod;
        }
    }

    /** One dependency a build file DECLARES — not one it inherits. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PomFact(String file, int line, String groupId, String artifactId, String version,
                   boolean managed) {}

    /**
     * Everything kept for one reference root.
     *
     * @param typesInFile the resolved types each file actually uses — the structural fingerprint
     *                    the nearest-example selector overlaps against a contract's demands. It is
     *                    the one thing here with no line number, because "which file" is the whole
     *                    question and a line would be a lie about a set.
     * @param degraded    "" when the whole root parsed; otherwise why it is partial. A partial
     *                    index still answers; it just says so.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class RootFacts {
        public String format = FORMAT;
        public String label = "";
        public String path = "";
        public String fingerprint = "";
        public long buildMillis;
        public int javaFiles;
        public int parsedFiles;
        public String degraded = "";
        public List<TypeFact> types = new ArrayList<>();
        public List<UseFact> uses = new ArrayList<>();
        public List<CallFact> calls = new ArrayList<>();
        public List<PomFact> poms = new ArrayList<>();
        public Map<String, List<String>> typesInFile = new LinkedHashMap<>();
    }

    /** Gzipped JSON. A few megabytes for a framework checkout; milliseconds to read back. */
    static void write(Path file, RootFacts facts) throws Exception {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(temp))) {
            JSON.writeValue(out, facts);
        }
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Null when there is nothing readable there — never an exception, this is a cache. */
    static RootFacts read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            RootFacts facts = JSON.readValue(in, RootFacts.class);
            return facts != null && FORMAT.equals(facts.format) ? facts : null;
        } catch (Exception e) {                                            // noqa
            log.debug("could not read the semantic index at {}: {}", file, e.getMessage());
            return null;
        }
    }
}
