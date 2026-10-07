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
package com.swarmcoder.console;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No source file in this checkout has been written as UTF-8 and read back as Windows-1252.
 *
 * <p><b>Why this is worth a test.</b> Section 12 of {@code docs/DEVELOPER_CORRECTIONS.md} records
 * that the shipped Console UI once had this everywhere: em dashes, the middle dot, the command
 * glyph and the multiplication sign all arriving on screen as three or four wrong characters. It
 * is a silent fault. Nothing errors, nothing warns, the build is green, and the only symptom is
 * that the person looking at the screen sees rubbish. It survives code review too, because the
 * damage is one literal in one file and it reads like a deliberate escape sequence.
 *
 * <p>It also arrives from outside. The published {@code zerozstack-ui-components 0.7.0} jar has
 * eight strings corrupted in exactly this way: {@code LaneTimeline}'s three play buttons, its
 * pause glyph and the ellipsis it shortens labels with, {@code DiffView}'s minus sign,
 * {@code PropertyGrid}'s em dash and {@code StreamingText}'s cursor. The Console draws several of
 * them. That is the framework's to fix and a version bump will bring it; this test guards OUR
 * side of the line, so the two can never be confused again.
 *
 * <h2>What it looks for</h2>
 *
 * <p>UTF-8 writes anything above ASCII as two or more bytes. Read those bytes back as
 * Windows-1252 and every byte becomes a character of its own, so one real character turns into
 * two or three: always one of a handful of lead characters, followed by another character above
 * ASCII. That pair is the signature, and correctly written text never produces it - no language
 * puts a capital A-tilde in front of a cent sign.
 *
 * <p>The replacement character is caught as well: that is what a decoder writes when the bytes
 * were not valid at all. A file that will not decode as UTF-8 is reported by name, not skipped.
 *
 * <h2>Why every character here is written as a number</h2>
 *
 * <p>This file is pure ASCII and must stay that way. A test about mis-encoded characters cannot
 * contain any of its own: if the two character classes below were typed as characters, this file
 * would match its own pattern and the test could never go green. Building them from code points
 * also documents exactly which byte values are being described.
 */
class NoMojibakeTest {

    /**
     * What the FIRST byte of a multi-byte UTF-8 sequence turns into under Windows-1252.
     *
     * <p>Five characters, not the whole range they sit in. These are the ones this disease
     * actually produces: a two-byte sequence for a Latin letter, or for a symbol such as the
     * section sign, starts 0xC2 or 0xC3, and a three-byte sequence for punctuation such as an em
     * dash, an ellipsis or a bullet starts 0xE2. Widening this to every accented capital would
     * start reporting ordinary European text as a fault.
     */
    private static final String LEAD = chars(0x00C2, 0x00C3, 0x00C5, 0x00C6, 0x00E2);

    /**
     * What its later bytes turn into: the characters Windows-1252 maps 0x80 to 0xBF onto.
     *
     * <p>A UTF-8 continuation byte is always in 0x80 to 0xBF. Windows-1252 maps 0xA0 to 0xBF onto
     * themselves; the 0x80 to 0x9F block holds the printable oddities that encoding is known for
     * - curly quotes, dashes, the euro sign - plus five values it leaves undefined, which
     * decoders pass through unchanged.
     */
    private static final String FOLLOW = follow();

    /** What a decoder writes when the bytes were not valid in the encoding at all. */
    private static final char REPLACEMENT = (char) 0xFFFD;

    private static final Pattern SIGNATURE =
        Pattern.compile("[" + LEAD + "][" + FOLLOW + "]|" + REPLACEMENT);

    @Test
    void noSourceFileIsDoubleEncoded() throws IOException {
        Path root = repositoryRoot();
        List<String> faults = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> tree = Files.walk(root)) {
            List<Path> sources = tree
                .filter(Files::isRegularFile)
                .filter(NoMojibakeTest::isSource)
                .filter(NoMojibakeTest::isNotBuildOutput)
                .toList();
            for (Path file : sources) {
                scanned++;
                String text;
                try {
                    text = Files.readString(file);
                } catch (MalformedInputException e) {
                    faults.add(root.relativize(file) + " is not valid UTF-8 at all");
                    continue;
                }
                String[] lines = text.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (SIGNATURE.matcher(lines[i]).find()) {
                        faults.add(root.relativize(file) + ":" + (i + 1) + "  " + lines[i].strip());
                    }
                }
            }
        }
        assertThat(scanned)
            .describedAs("the walk found the checkout - a test that scans nothing would pass for "
                + "the emptiest possible reason")
            .isGreaterThan(200);
        assertThat(faults)
            .describedAs("a source file has been written as UTF-8 and read back as Windows-1252. "
                + "Whatever is on those lines reaches the screen as several wrong characters, and "
                + "nothing else will warn about it. Retype the characters; do not try to fix it "
                + "by changing an encoding setting, because the bytes on disk are already wrong")
            .isEmpty();
    }

    private static String chars(int... codePoints) {
        StringBuilder sb = new StringBuilder();
        for (int codePoint : codePoints) {
            sb.append((char) codePoint);
        }
        return sb.toString();
    }

    private static String follow() {
        StringBuilder sb = new StringBuilder();
        // The Windows-1252 0x80-0x9F block, in byte order.
        sb.append(chars(0x20AC, 0x0081, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
            0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x008D, 0x017D, 0x008F,
            0x0090, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
            0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x009D, 0x017E, 0x0178));
        // 0xA0-0xBF, which map onto themselves.
        for (int codePoint = 0x00A0; codePoint <= 0x00BF; codePoint++) {
            sb.append((char) codePoint);
        }
        return sb.toString();
    }

    private static boolean isSource(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".css") || name.endsWith(".html")
            || name.endsWith(".properties");
    }

    /** {@code target} holds copies of everything; scanning it would report every fault twice. */
    private static boolean isNotBuildOutput(Path file) {
        for (Path part : file) {
            String name = part.toString();
            if (name.equals("target") || name.equals("node_modules") || name.equals(".git")) {
                return false;
            }
        }
        return true;
    }

    /** The directory holding the aggregator pom, found by walking up from this module. */
    private static Path repositoryRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (Path at = here; at != null; at = at.getParent()) {
            if (Files.exists(at.resolve("sc-console").resolve("pom.xml"))
                    && Files.exists(at.resolve("pom.xml"))) {
                return at;
            }
        }
        return here;
    }
}
