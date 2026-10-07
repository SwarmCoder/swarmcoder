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
package com.swarmcoder.verify;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §18: regex + entropy scan over ADDED diff lines only, with redacted findings. */
class SecretScannerTest {

    private final SecretScanner scanner = new SecretScanner();

    @Test
    void flagsKnownTokenFormatsOnAddedLines() {
        List<SecretScanner.Finding> findings = scanner.scan("""
            --- a/App.java
            +++ b/App.java
            @@ -1,2 +1,3 @@
             context line
            +String key = "AKIAIOSFODNN7EXAMPLE";
            """);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).kind()).isEqualTo("known-token-format");
        assertThat(findings.get(0).line()).contains("[redacted]").doesNotContain("AKIAIOSFODNN7EXAMPLE");
    }

    @Test
    void flagsHighEntropyCredentialAssignments() {
        List<SecretScanner.Finding> findings = scanner.scan(
            "+apiKey: \"g7Xq2PzR9kVbW4tYmC1sJfL8\"\n");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).kind()).isEqualTo("high-entropy-credential");
        assertThat(findings.get(0).line()).doesNotContain("g7Xq2PzR9kVbW4tYmC1sJfL8");
    }

    @Test
    void ignoresPlaceholdersAndPreexistingContent() {
        // Low-entropy placeholder value; secrets on context/removed lines; a private key
        // header that was REMOVED — none of these are the run's doing.
        List<SecretScanner.Finding> findings = scanner.scan("""
            +password = "xxxxxxxxxxxxxxxxxxxx"
             apiKey: "g7Xq2PzR9kVbW4tYmC1sJfL8"
            -token = "g7Xq2PzR9kVbW4tYmC1sJfL8"
            -----BEGIN RSA PRIVATE KEY-----
            """);
        assertThat(findings).isEmpty();
    }

    @Test
    void flagsAddedPrivateKeyBlocks() {
        assertThat(scanner.scan("+-----BEGIN OPENSSH PRIVATE KEY-----\n"))
            .singleElement()
            .extracting(SecretScanner.Finding::kind).isEqualTo("known-token-format");
    }

    @Test
    void emptyOrNullDiffIsClean() {
        assertThat(scanner.scan(null)).isEmpty();
        assertThat(scanner.scan("")).isEmpty();
    }

    /**
     * Audit of 2026-10-02: a method call assigned to something called token or secret is code, not
     * a credential. Flagged, it failed final integration, the last step of a finished run.
     */
    @Test
    void aMethodCallAssignedToATokenIsNotACredential() {
        SecretScanner scanner = new SecretScanner();
        String code = "+++ b/src/main/java/Auth.java\n"
            + "+        String token = generateSecureSessionTokenFor(user);\n"
            + "+        this.secret = credentialsProviderRegistry.current();\n";
        String literal = "+++ b/src/main/java/Auth.java\n"
            + "+        String token = \"q8Zr4mVx91LpTt0aHs6dWk3e\";\n";
        String config = "+++ b/app.properties\n+api_key=q8Zr4mVx91LpTt0aHs6dWk3e\n";

        org.assertj.core.api.Assertions.assertThat(scanner.scan(code)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(scanner.scan(literal)).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(scanner.scan(config)).hasSize(1);
    }
}
