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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Which library types a server container looks for on the classpath, so that a class of the
 * project which is one of them runs although no code names it. Read, not asked: no model, nothing
 * loaded, nothing run.
 *
 * <h2>The run this exists because of (live local build, 2026-10-14)</h2>
 *
 * <p>A build was stopped twice, the second time after a repair round, for "production code that
 * nothing in the application can reach": a class implementing the Jakarta WebSocket
 * {@code ServerApplicationConfig}. Nothing names such a class, by design: the container scans
 * the deployment for implementations and asks each which endpoints to deploy. {@link
 * ReachableCode} learns discovery from the project's own older code, and the project had no
 * older class of that kind; found by its supertype alone, with no annotation, it had no rule.
 *
 * <h2>Two sources, both a matter of record</h2>
 *
 * <ol>
 *   <li><b>What the libraries on the classpath declare.</b> The servlet standard has one
 *       mechanism by which any library says "hand me every class of the application that is one
 *       of these": a {@code ServletContainerInitializer} registered in the library's own
 *       {@code META-INF/services}, carrying {@code @HandlesTypes}. Those are read from the jars
 *       and class folders the tree is parsed with ({@link #handledTypes}). This covers any
 *       framework that uses the mechanism, with no name of any framework here.</li>
 *   <li><b>What the platform standards themselves require a container to find</b>
 *       ({@link #STANDARD_SUPERTYPES}, {@link #STANDARD_ANNOTATIONS}). Needed because source 1
 *       is declared by the container's implementation, and a project is normally compiled
 *       against the standard's API jar only: the implementation is supplied where the
 *       application is deployed and is not on the classpath this reads. The list is the
 *       deployment-by-scanning contracts of Jakarta Servlet, WebSocket and REST, in both the
 *       {@code jakarta} and the older {@code javax} namespace, and nothing of any product.</li>
 * </ol>
 *
 * <p>An interface of the project's own that no registered initializer asks for is found by no
 * container: implementing it explains nothing. A name of the standard list that the project
 * declares itself is not the standard's type and does not count.
 *
 * <h2>What this cannot see</h2>
 *
 * <ul>
 *   <li><b>What the library does with a class it is handed.</b> A handled supertype is taken as
 *       "found and run". A library may ask for a broad type and use only some of the classes.</li>
 *   <li><b>Annotations a library asks for are not taken from source 1.</b> Libraries ask for
 *       annotations that mark a class without making it an entry (a stylesheet import, say),
 *       and a class nobody opens would pass for carrying one. Discovery by annotation stays
 *       with what {@link ReachableCode} learns from the project and with the standard list.</li>
 *   <li><b>Discovery a library implements by itself</b>: its own classpath scan, or a
 *       registration file of its own format that does not name the class.</li>
 * </ul>
 */
final class ContainerDiscovery {

    private static final Logger log = LoggerFactory.getLogger(ContainerDiscovery.class);

    /** The two namespaces the platform's standards were published under. */
    private static final List<String> NAMESPACES = List.of("jakarta.", "javax.");

    /**
     * Supertypes a container finds by scanning, with no annotation and no registration:
     * Jakarta WebSocket, "Application deployment on web containers" - the container scans the
     * application for implementations of {@code ServerApplicationConfig}. (An {@code Endpoint}
     * subclass is NOT here: the scan only passes it to such a configuration, which decides.)
     */
    static final Set<String> STANDARD_SUPERTYPES = bothNamespaces(
        "websocket.server.ServerApplicationConfig");

    /**
     * Type annotations a container deploys by scanning: Servlet ("Annotations and
     * pluggability"), WebSocket (annotated endpoints) and REST (an application with no explicit
     * class list is assembled from the root resource and provider classes found).
     */
    static final Set<String> STANDARD_ANNOTATIONS = bothNamespaces(
        "servlet.annotation.WebServlet", "servlet.annotation.WebFilter",
        "servlet.annotation.WebListener", "websocket.server.ServerEndpoint",
        "ws.rs.ApplicationPath", "ws.rs.Path", "ws.rs.ext.Provider");

    private static final Set<String> INITIALIZER_SERVICES = bothNamespaces(
        "servlet.ServletContainerInitializer");
    private static final Set<String> HANDLES_TYPES = bothNamespaces(
        "servlet.annotation.HandlesTypes");

    private static final long MAX_ENTRY_BYTES = 2 * 1024 * 1024L;

    private ContainerDiscovery() {
    }

    private static Set<String> bothNamespaces(String... names) {
        Set<String> all = new TreeSet<>();
        for (String namespace : NAMESPACES) {
            for (String name : names) {
                all.add(namespace + name);
            }
        }
        return Set.copyOf(all);
    }

    /**
     * The types the libraries of a classpath ask the servlet container to hand them: the values
     * of {@code @HandlesTypes} on every initializer a jar or class folder registers in its
     * {@code META-INF/services}. Full names, nested types with {@code $}. Never throws; an
     * entry that cannot be read contributes nothing.
     */
    static Set<String> handledTypes(Collection<Path> classpath) {
        Set<String> handled = new TreeSet<>();
        for (Path entry : classpath == null ? List.<Path>of() : classpath) {
            try {
                if (Files.isDirectory(entry)) {
                    readFolder(entry, handled);
                } else if (Files.isRegularFile(entry)) {
                    readJar(entry, handled);
                }
            } catch (Exception | LinkageError e) {                         // noqa
                log.debug("could not read what {} asks a container for: {}", entry, e.toString());
            }
        }
        return handled;
    }

    private static void readFolder(Path folder, Set<String> into) throws Exception {
        for (String service : INITIALIZER_SERVICES) {
            Path registration = folder.resolve("META-INF/services/" + service);
            if (!Files.isRegularFile(registration)
                    || Files.size(registration) > MAX_ENTRY_BYTES) {
                continue;
            }
            for (String initializer : registered(Files.readAllBytes(registration))) {
                Path clazz = folder.resolve(initializer.replace('.', '/') + ".class");
                if (Files.isRegularFile(clazz) && Files.size(clazz) <= MAX_ENTRY_BYTES) {
                    into.addAll(handledBy(Files.readAllBytes(clazz)));
                }
            }
        }
    }

    private static void readJar(Path jar, Set<String> into) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String service : INITIALIZER_SERVICES) {
                byte[] registration = bytesOf(zip, "META-INF/services/" + service);
                for (String initializer : registered(registration)) {
                    byte[] clazz = bytesOf(zip, initializer.replace('.', '/') + ".class");
                    if (clazz.length > 0) {
                        into.addAll(handledBy(clazz));
                    }
                }
            }
        }
    }

    private static byte[] bytesOf(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory() || entry.getSize() > MAX_ENTRY_BYTES) {
            return new byte[0];
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readNBytes((int) MAX_ENTRY_BYTES);
        }
    }

    /** The class names of a service-loader file: one a line, {@code #} starts a comment. */
    private static List<String> registered(byte[] file) {
        List<String> names = new ArrayList<>();
        for (String line : new String(file, StandardCharsets.UTF_8).split("\\R")) {
            int comment = line.indexOf('#');
            String name = (comment < 0 ? line : line.substring(0, comment)).strip();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /** The classes one compiled initializer names in its {@code @HandlesTypes}. */
    private static List<String> handledBy(byte[] clazz) {
        ClassNode node = new ClassNode();
        new ClassReader(clazz).accept(node,
            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        List<String> found = new ArrayList<>();
        List<AnnotationNode> annotations = new ArrayList<>();
        if (node.visibleAnnotations != null) {
            annotations.addAll(node.visibleAnnotations);
        }
        if (node.invisibleAnnotations != null) {
            annotations.addAll(node.invisibleAnnotations);
        }
        for (AnnotationNode annotation : annotations) {
            if (!HANDLES_TYPES.contains(Type.getType(annotation.desc).getClassName())
                    || annotation.values == null) {
                continue;
            }
            // name, value, name, value ...; the one element is an array of class constants
            for (int i = 1; i < annotation.values.size(); i += 2) {
                if (annotation.values.get(i) instanceof List<?> classes) {
                    for (Object each : classes) {
                        if (each instanceof Type type && type.getSort() == Type.OBJECT) {
                            found.add(type.getClassName());
                        }
                    }
                }
            }
        }
        return found;
    }
}
