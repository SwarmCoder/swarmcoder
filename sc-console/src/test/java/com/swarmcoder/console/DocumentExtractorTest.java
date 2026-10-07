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

import com.swarmcoder.console.DocumentExtractor.Extracted;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Covers the extractor stage of the BRD intake pipeline. The PDF and .docx cases build a real
 * document with the same library that reads it back, so the test exercises the actual parser
 * rather than a fixture that could drift.
 */
class DocumentExtractorTest {

    // --- passthrough ----------------------------------------------------------------------

    @Test
    void markdownGoesThroughUntouched() {
        byte[] bytes = "# Title\n\nA requirement.".getBytes(StandardCharsets.UTF_8);

        Extracted e = DocumentExtractor.extract("brd.md", "text/markdown", bytes);

        assertThat(e.error()).isNull();
        assertThat(e.extractedBy()).isEqualTo("passthrough");
        assertThat(e.text()).isEqualTo("# Title\n\nA requirement.");
        assertThat(DocumentExtractor.needsVision(e)).isFalse();
    }

    @Test
    void everyTextExtensionIsPassthrough() {
        for (String name : List.of("a.md", "a.markdown", "a.txt", "a.adoc", "a.rst", "a.csv")) {
            Extracted e = DocumentExtractor.extract(name, null, "hello".getBytes(StandardCharsets.UTF_8));
            assertThat(e.error()).as(name).isNull();
            assertThat(e.extractedBy()).as(name).isEqualTo("passthrough");
            assertThat(e.text()).as(name).isEqualTo("hello");
        }
    }

    @Test
    void bomAndCrlfAreNormalised() {
        String raw = "﻿line one\r\nline two\r\n";

        Extracted e = DocumentExtractor.extract("notes.txt", "text/plain", raw.getBytes(StandardCharsets.UTF_8));

        assertThat(e.error()).isNull();
        assertThat(e.text())
                .isEqualTo("line one\nline two")
                .doesNotContain("﻿")
                .doesNotContain("\r");
    }

    @Test
    void runsOfBlankLinesCollapseToOne() {
        String raw = "para one\n\n\n\n\n\npara two\n\n\npara three";

        Extracted e = DocumentExtractor.extract("notes.txt", "text/plain", raw.getBytes(StandardCharsets.UTF_8));

        assertThat(e.text()).isEqualTo("para one\n\npara two\n\npara three");
    }

    @Test
    void leadingAndTrailingWhitespaceIsTrimmed() {
        Extracted e = DocumentExtractor.extract("notes.txt", null,
                "\n\n   body   \n\n\n".getBytes(StandardCharsets.UTF_8));

        assertThat(e.text()).isEqualTo("body");
    }

    @Test
    void unknownExtensionFallsBackToTextMediaType() {
        Extracted e = DocumentExtractor.extract("README", "text/plain", "hi".getBytes(StandardCharsets.UTF_8));

        assertThat(e.error()).isNull();
        assertThat(e.extractedBy()).isEqualTo("passthrough");
        assertThat(e.text()).isEqualTo("hi");
    }

    // --- pdf ------------------------------------------------------------------------------

    @Test
    void realPdfRoundTrips() throws Exception {
        byte[] pdf = pdf("The system shall accept uploaded documents.");

        Extracted e = DocumentExtractor.extract("spec.pdf", "application/pdf", pdf);

        assertThat(e.error()).isNull();
        assertThat(e.extractedBy()).isEqualTo("pdfbox");
        assertThat(e.text()).contains("The system shall accept uploaded documents.");
        assertThat(e.text()).doesNotContain("\r");
        assertThat(DocumentExtractor.needsVision(e)).isFalse();
    }

    @Test
    void pdfIsDetectedByMediaTypeWhenTheFilenameHasNoExtension() throws Exception {
        byte[] pdf = pdf("Detected without an extension.");

        Extracted e = DocumentExtractor.extract("upload", "application/pdf", pdf);

        assertThat(e.error()).isNull();
        assertThat(e.extractedBy()).isEqualTo("pdfbox");
        assertThat(e.text()).contains("Detected without an extension.");
    }

    @Test
    void corruptPdfReturnsAnErrorInsteadOfThrowing() {
        byte[] garbage = "%PDF-1.7 this is not actually a pdf at all".getBytes(StandardCharsets.UTF_8);

        assertThatCode(() -> {
            Extracted e = DocumentExtractor.extract("broken.pdf", "application/pdf", garbage);
            assertThat(e.error()).isNotNull();
            assertThat(e.text()).isNull();
        }).doesNotThrowAnyException();
    }

    @Test
    void emptyBytesForAPdfReturnAnError() {
        Extracted e = DocumentExtractor.extract("empty.pdf", "application/pdf", new byte[0]);

        assertThat(e.error()).isNotNull();
        assertThat(e.text()).isNull();
    }

    // --- docx -----------------------------------------------------------------------------

    @Test
    void realDocxRoundTrips() throws Exception {
        byte[] docx = docx("Requirement one.", "Requirement two.");

        Extracted e = DocumentExtractor.extract("spec.docx", null, docx);

        assertThat(e.error()).isNull();
        assertThat(e.extractedBy()).isEqualTo("poi");
        assertThat(e.text()).contains("Requirement one.").contains("Requirement two.");
    }

    @Test
    void corruptDocxReturnsAnErrorInsteadOfThrowing() {
        Extracted e = DocumentExtractor.extract("broken.docx", null, "PK not really a zip".getBytes(StandardCharsets.UTF_8));

        assertThat(e.error()).isNotNull();
        assertThat(e.text()).isNull();
    }

    // --- legacy .doc ----------------------------------------------------------------------

    @Test
    void legacyDocIsRejectedWithAnActionableMessage() {
        Extracted e = DocumentExtractor.extract("old.doc", "application/msword", new byte[]{1, 2, 3});

        assertThat(e.text()).isNull();
        assertThat(e.error()).isNotNull();
        assertThat(e.error()).containsIgnoringCase(".doc").containsIgnoringCase(".docx");
        assertThat(DocumentExtractor.needsVision(e)).isFalse();
    }

    // --- images ---------------------------------------------------------------------------

    @Test
    void imagesSignalThatTheVisionModelIsNeeded() {
        for (String name : List.of("a.png", "a.jpg", "a.jpeg", "a.webp", "a.gif")) {
            Extracted e = DocumentExtractor.extract(name, null, new byte[]{1, 2, 3});
            assertThat(DocumentExtractor.needsVision(e)).as(name).isTrue();
            assertThat(e.extractedBy()).as(name).isEqualTo("vision");
            assertThat(e.text()).as(name).isNull();
            assertThat(e.error()).as(name).isNull();
        }
    }

    @Test
    void imageMediaTypeAlsoSignalsVision() {
        Extracted e = DocumentExtractor.extract("pasted", "image/png", new byte[]{1, 2, 3});

        assertThat(DocumentExtractor.needsVision(e)).isTrue();
    }

    @Test
    void needsVisionIsFalseForNullAndForOrdinaryResults() {
        assertThat(DocumentExtractor.needsVision(null)).isFalse();
        assertThat(DocumentExtractor.needsVision(new Extracted("text", "passthrough", null))).isFalse();
        assertThat(DocumentExtractor.needsVision(new Extracted(null, "vision", "boom"))).isFalse();
    }

    // --- unsupported ----------------------------------------------------------------------

    @Test
    void unknownExtensionIsRejectedAndListsWhatIsSupported() {
        Extracted e = DocumentExtractor.extract("archive.zip", "application/zip", new byte[]{1, 2, 3});

        assertThat(e.text()).isNull();
        assertThat(e.error()).contains(".zip").contains(".pdf").contains(".docx");
    }

    @Test
    void aTextMediaTypeCannotSmuggleAnExecutableThrough() {
        Extracted e = DocumentExtractor.extract("payload.exe", "text/plain", new byte[]{1, 2, 3});

        assertThat(e.error()).isNotNull();
        assertThat(e.text()).isNull();
    }

    @Test
    void nothingIdentifiableIsRejected() {
        Extracted e = DocumentExtractor.extract("upload", "application/octet-stream", new byte[]{1, 2, 3});

        assertThat(e.error()).isNotNull();
        assertThat(e.text()).isNull();
    }

    @Test
    void nullBytesAreRejectedRatherThanThrowing() {
        assertThatCode(() -> assertThat(DocumentExtractor.extract("a.md", "text/plain", null).error()).isNotNull())
                .doesNotThrowAnyException();
    }

    // --- cap ------------------------------------------------------------------------------

    @Test
    void textBeyondTheCapIsTruncatedWithAMarker() {
        String over = "x".repeat(DocumentExtractor.MAX_EXTRACTED_CHARS + 500);

        Extracted e = DocumentExtractor.extract("big.txt", "text/plain", over.getBytes(StandardCharsets.UTF_8));

        assertThat(e.error()).isNull();
        assertThat(e.text()).startsWith("x").endsWith("[truncated: 500 more characters]");
        assertThat(e.text()).hasSizeGreaterThan(DocumentExtractor.MAX_EXTRACTED_CHARS);
        assertThat(e.text().indexOf('\n')).isEqualTo(DocumentExtractor.MAX_EXTRACTED_CHARS);
    }

    @Test
    void textAtTheCapIsNotTruncated() {
        String exact = "y".repeat(DocumentExtractor.MAX_EXTRACTED_CHARS);

        Extracted e = DocumentExtractor.extract("big.txt", "text/plain", exact.getBytes(StandardCharsets.UTF_8));

        assertThat(e.text()).hasSize(DocumentExtractor.MAX_EXTRACTED_CHARS).doesNotContain("truncated");
    }

    // --- fixtures -------------------------------------------------------------------------

    /** Builds a genuine single-page PDF with PDFBox so the round trip exercises the real parser. */
    private static byte[] pdf(String line) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(line);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Builds a genuine .docx with POI. */
    private static byte[] docx(String... paragraphs) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String p : paragraphs) {
                XWPFParagraph para = doc.createParagraph();
                XWPFRun run = para.createRun();
                run.setText(p);
            }
            doc.write(out);
            return out.toByteArray();
        }
    }
}
