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
package com.swarmcoder.syntax;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureExtractionTest {

    @TempDir
    Path dir;

    @Test
    void extractsJavaSignatures() throws Exception {
        Path file = dir.resolve("Calculator.java");
        Files.writeString(file, """
            package com.example;
            public class Calculator {
                public int add(int a, int b) { return a + b; }
                public int divide(int a, int b) { return a / b; }
            }
            """);

        List<SymbolSig> signatures = new TreeSitterSyntaxService().signatures(file);

        assertThat(signatures).extracting(SymbolSig::signature)
            .anyMatch(s -> s.contains("class Calculator"))
            .anyMatch(s -> s.contains("int add(int a, int b)"))
            .anyMatch(s -> s.contains("int divide(int a, int b)"));
    }
}
