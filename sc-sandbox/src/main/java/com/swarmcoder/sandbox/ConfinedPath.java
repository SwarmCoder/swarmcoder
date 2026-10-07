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
package com.swarmcoder.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The one check every path a model supplies goes through before a file tool touches the disk.
 *
 * <p>The file tools (a worker's {@code read}, the expert's and the roles' {@code read_file} and
 * {@code list_files}) run in the orchestrator's own process, on the workstation, whatever the
 * model's shell is confined to. So the root they are given is the whole of their containment, and
 * three ways out of it have to be closed in the same place:
 *
 * <ul>
 *   <li><b>absolute paths and drive letters</b> ({@code /etc/passwd}, {@code C:\Users},
 *       {@code \\server\share}, {@code ~/.ssh}) - refused outright, never "helpfully" re-rooted;</li>
 *   <li><b>{@code ..} escapes</b> - checked on the normalised path;</li>
 *   <li><b>symbolic links</b> - {@link Path#normalize()} is lexical and walks straight through a
 *       link that points out of the root, and a worker's shell can create one inside its own
 *       checkout. The deepest existing ancestor is resolved with {@code toRealPath} and compared
 *       against the root's own real path.</li>
 * </ul>
 *
 * <p>Lives in this module because it is the lowest one every file-tool module already depends on,
 * and because it is the same subject as the container: what model-chosen input can reach.
 */
public final class ConfinedPath {

    /** {@code C:}, {@code c:\x}, {@code C:foo} - a drive, absolute or drive-relative. */
    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:.*");

    private ConfinedPath() {
    }

    /**
     * @param path    the resolved, normalised path inside the root; null when refused
     * @param refusal why the path was refused, written for the model that supplied it; null when
     *                allowed
     */
    public record Result(Path path, String refusal) {

        public boolean allowed() {
            return refusal == null;
        }
    }

    /**
     * Resolves a model-supplied path against {@code root}. The path must be relative.
     *
     * @param what what the root is, in the refusal's words - e.g. {@code "your checkout"}
     */
    public static Result resolve(Path root, String raw, String what) {
        if (root == null) {
            return refused("there is no folder to read from");
        }
        if (raw == null || raw.isBlank()) {
            return refused("no path was given; give a path relative to " + what);
        }
        String cleaned = raw.strip().replace('\\', '/');
        if (cleaned.indexOf('\0') >= 0) {
            return refused("`" + raw.strip() + "` is not a usable path");
        }
        if (cleaned.startsWith("/") || cleaned.startsWith("~") || DRIVE.matcher(cleaned).matches()) {
            return refused("`" + raw.strip() + "` is an absolute path. Only " + what
                + " can be read, and only by a path relative to it (for example "
                + "`src/main/java/App.java`); nothing else on this machine is reachable");
        }
        Path base = root.toAbsolutePath().normalize();
        Path target;
        try {
            target = base.resolve(cleaned).normalize();
        } catch (RuntimeException e) {
            return refused("`" + raw.strip() + "` is not a usable path");
        }
        if (!target.startsWith(base)) {
            return refused("`" + raw.strip() + "` leads outside " + what
                + " (`..` may not climb above its root); nothing outside it is reachable");
        }
        if (!inside(base, target)) {
            return refused("`" + raw.strip() + "` goes through a symbolic link that leads outside "
                + what + "; nothing outside it is reachable");
        }
        return new Result(target, null);
    }

    /**
     * True when {@code target} is inside {@code root} both lexically and after symbolic links are
     * resolved. For paths the product built itself (which may legitimately be absolute) as well as
     * the second half of {@link #resolve}.
     */
    public static boolean inside(Path root, Path target) {
        if (root == null || target == null) {
            return false;
        }
        Path base = root.toAbsolutePath().normalize();
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(base)) {
            return false;
        }
        Path realTarget = real(normalized);
        return realTarget != NOWHERE && realTarget.startsWith(real(base));
    }

    /** As {@link #inside}, throwing the way the exec targets' file operations report an escape. */
    public static void requireInside(Path root, Path target, String given) throws IOException {
        if (!inside(root, target)) {
            throw new IOException("Path escapes workspace: " + given);
        }
    }

    /**
     * The real path of the deepest existing ancestor with the rest appended - {@code toRealPath}
     * throws for a file that does not exist yet, which is the normal case for a new one.
     */
    private static Path real(Path path) {
        Path existing = path;
        List<String> trailing = new ArrayList<>();
        // NOFOLLOW on purpose: a DANGLING link has to count as existing, or it would be skipped as
        // "a file not written yet" and a write through it would create its target, outside.
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path name = existing.getFileName();
            if (name != null) {
                trailing.add(0, name.toString());
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            return path;
        }
        Path real;
        try {
            real = existing.toRealPath();
        } catch (IOException | RuntimeException e) {
            if (Files.isSymbolicLink(existing)) {
                return NOWHERE; // a link whose target cannot be resolved is never "inside"
            }
            real = existing;
        }
        for (String segment : trailing) {
            real = real.resolve(segment);
        }
        return real.normalize();
    }

    /** A path that is inside no root: what an unresolvable link resolves to. */
    private static final Path NOWHERE = Path.of("unresolvable-link");

    private static Result refused(String why) {
        return new Result(null, "Refused: " + why + ".");
    }
}
