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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.treesitter.TSParser;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterPython;
import org.treesitter.TreeSitterHtml;
import org.treesitter.TreeSitterCss;
import org.treesitter.TreeSitterRust;
import org.treesitter.TreeSitterTypescript;
import org.treesitter.TSTree;
import org.treesitter.TSNode;
import org.treesitter.TSLanguage;
import com.swarmcoder.syntax.Language;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class TreeSitterSyntaxService implements SyntaxService {

    public TreeSitterSyntaxService() {
        // Initialization/loading native libs happens here
    }

    private TSLanguage getTSLanguage(Language lang) {
        return switch (lang) {
            case JAVA -> new TreeSitterJava();
            case PYTHON -> new TreeSitterPython();
            case HTML -> new TreeSitterHtml();
            case CSS -> new TreeSitterCss();
            case RUST -> new TreeSitterRust();
            case TYPESCRIPT -> new TreeSitterTypescript();
            case TSX -> new TreeSitterTypescript(); // fallback
            default -> new TreeSitterJava(); // Fallback
        };
    }

    @Override
    public ParseVerdict parse(Language lang, byte[] source) {
        TSParser parser = new TSParser();
        parser.setLanguage(getTSLanguage(lang));
        TSTree tree = parser.parseString(null, new String(source));
        boolean hasError = tree.getRootNode().hasError();
        List<SyntaxError> errors = new ArrayList<>();
        if (hasError) {
            errors.add(new SyntaxError(0, 0, 0, 0, "Syntax Error")); // Stubbed full traversal
        }
        return new ParseVerdict(!hasError, errors);
    }

    @Override
    public RepoMap repoMap(Path repoRoot, Set<String> includeGlobs) {
        return new RepoMap("Stub repo map content for M2");
    }

    /**
     * Signature extraction for the repo map and KnowledgeBriefs. Java only for now: class,
     * interface, record, method and constructor declarations, each as its header text
     * (declaration start to body start).
     */
    @Override
    public List<SymbolSig> signatures(Path file) {
        if (!file.toString().endsWith(".java")) {
            return new ArrayList<>();
        }
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            return new ArrayList<>();
        }
        TSParser parser = new TSParser();
        parser.setLanguage(new TreeSitterJava());
        TSTree tree = parser.parseString(null, source);
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        List<SymbolSig> signatures = new ArrayList<>();
        collectSignatures(tree.getRootNode(), bytes, signatures);
        return signatures;
    }

    private static final Set<String> DECLARATIONS = Set.of(
        "class_declaration", "interface_declaration", "record_declaration",
        "enum_declaration", "method_declaration", "constructor_declaration");

    private static void collectSignatures(TSNode node, byte[] source, List<SymbolSig> out) {
        if (DECLARATIONS.contains(node.getType())) {
            TSNode body = node.getChildByFieldName("body");
            int end = body != null && !body.isNull() ? body.getStartByte() : node.getEndByte();
            String header = new String(source, node.getStartByte(), end - node.getStartByte(),
                StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ").trim();
            TSNode name = node.getChildByFieldName("name");
            String symbol = name != null && !name.isNull()
                ? new String(source, name.getStartByte(), name.getEndByte() - name.getStartByte(),
                    StandardCharsets.UTF_8)
                : header;
            out.add(new SymbolSig(symbol, header, "",
                node.getStartPoint().getRow() + 1, node.getEndPoint().getRow() + 1));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectSignatures(node.getChild(i), source, out);
        }
    }

    @Override
    public String normalizeForClustering(Language lang, String diffHunkContext) {
        TSParser parser = new TSParser();
        parser.setLanguage(getTSLanguage(lang));
        TSTree tree = parser.parseString(null, diffHunkContext);
        return normalizeNode(tree.getRootNode(), diffHunkContext);
    }
    
    private String normalizeNode(TSNode node, String source) {
        if (node.getChildCount() == 0) {
            String type = node.getType();
            if (type.equals("comment") || type.equals("line_comment") || type.equals("block_comment")) {
                return "";
            }
            // For clustering, we strip whitespace and exact identifiers, replacing with structure
            return " " + type; 
        }
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < node.getChildCount(); i++) {
            sb.append(normalizeNode(node.getChild(i), source));
        }
        return sb.toString();
    }
}
