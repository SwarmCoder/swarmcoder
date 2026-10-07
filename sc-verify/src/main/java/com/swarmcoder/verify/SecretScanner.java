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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secret scan over a unified diff (spec §18): regex for known credential formats plus a
 * Shannon-entropy check on credential-named assignments. Only ADDED lines are scanned —
 * a secret that already sits in the target repo is not this run's doing and must not park
 * it. Findings carry the offending line with the value redacted, never the secret itself.
 */
public class SecretScanner {

    /** @param kind which detector fired; {@code line} is the added line with the value redacted */
    public record Finding(String kind, String line) {}

    /** Token shapes that are secrets regardless of surrounding context. */
    private static final Pattern KNOWN_TOKEN = Pattern.compile(
        "AKIA[0-9A-Z]{16}"                                  // AWS access key id
        + "|(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36,}"       // GitHub tokens
        + "|xox[baprs]-[A-Za-z0-9-]{10,}"                   // Slack tokens
        + "|sk-[A-Za-z0-9]{20,}"                            // OpenAI-style keys
        + "|-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----");

    /** {@code name = "value"} where the name smells like a credential; value checked for entropy. */
    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile(
        "(?i)(?:api[_-]?key|secret|token|passwd|password|credential|"
        + "aws_access_key_id|aws_secret_access_key)"
        + "\\s*[:=]+\\s*[\"']?([A-Za-z0-9+/_=\\-]{16,})[\"']?");

    /**
     * Bits/char below which an assigned value is treated as a placeholder, not a secret
     * ("changeme-changeme", "xxxxxxxxxxxxxxxx"). Real keys are near-random: ≥ 4 bits/char.
     */
    private static final double ENTROPY_THRESHOLD = 3.5;

    public List<Finding> scan(String unifiedDiff) {
        List<Finding> findings = new ArrayList<>();
        if (unifiedDiff == null || unifiedDiff.isEmpty()) {
            return findings;
        }
        for (String raw : unifiedDiff.split("\n", -1)) {
            if (!raw.startsWith("+") || raw.startsWith("+++")) {
                continue; // context, removed, and header lines are pre-existing content
            }
            String line = raw.substring(1);
            Matcher known = KNOWN_TOKEN.matcher(line);
            if (known.find()) {
                findings.add(new Finding("known-token-format", redact(line, known.group())));
                continue;
            }
            Matcher assignment = CREDENTIAL_ASSIGNMENT.matcher(line);
            if (assignment.find() && !isAnExpression(line, assignment)
                    && shannonEntropy(assignment.group(1)) >= ENTROPY_THRESHOLD) {
                findings.add(new Finding("high-entropy-credential", redact(line, assignment.group(1))));
            }
        }
        return findings;
    }

    /**
     * Whether what was assigned is code rather than a value: an unquoted name followed by a call
     * or a member access, as in {@code token = generateSessionToken(user)} or
     * {@code secret = credentialsProvider.current()}. A long method name has the entropy of a
     * password and is not one; a quoted string, or a bare value with nothing called on it, still
     * is. Known token formats are caught before this is ever asked.
     */
    private static boolean isAnExpression(String line, Matcher assignment) {
        int start = assignment.start(1);
        boolean quoted = start > 0 && (line.charAt(start - 1) == '"' || line.charAt(start - 1) == '\'');
        if (quoted) {
            return false;
        }
        int end = assignment.end(1);
        return end < line.length() && (line.charAt(end) == '(' || line.charAt(end) == '.');
    }

    private static String redact(String line, String value) {
        String stub = value.length() > 4 ? value.substring(0, 4) + "…[redacted]" : "…[redacted]";
        return line.replace(value, stub).strip();
    }

    private static double shannonEntropy(String value) {
        Map<Character, Integer> counts = new HashMap<>();
        for (int i = 0; i < value.length(); i++) {
            counts.merge(value.charAt(i), 1, Integer::sum);
        }
        double entropy = 0;
        for (int count : counts.values()) {
            double p = (double) count / value.length();
            entropy -= p * Math.log(p) / Math.log(2);
        }
        return entropy;
    }
}
