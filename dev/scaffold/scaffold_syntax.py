import os

SYNTAX_DIR = "sc-syntax/src/main/java/com/swarmcoder/syntax"
os.makedirs(SYNTAX_DIR, exist_ok=True)

models = {
    os.path.join(SYNTAX_DIR, "Language.java"): """package com.swarmcoder.syntax;

public enum Language {
    JAVA, HTML, CSS, RUST, TYPESCRIPT, TSX, PYTHON, JSON, YAML
}
""",
    os.path.join(SYNTAX_DIR, "ParseVerdict.java"): """package com.swarmcoder.syntax;

import java.util.List;

public record ParseVerdict(boolean success, List<SyntaxError> errors) {}
""",
    os.path.join(SYNTAX_DIR, "SyntaxError.java"): """package com.swarmcoder.syntax;

public record SyntaxError(int startLine, int startColumn, int endLine, int endColumn, String message) {}
""",
    os.path.join(SYNTAX_DIR, "RepoMap.java"): """package com.swarmcoder.syntax;

public record RepoMap(String content) {}
""",
    os.path.join(SYNTAX_DIR, "SymbolSig.java"): """package com.swarmcoder.syntax;

public record SymbolSig(String name, String signature, String docComment, int startLine, int endLine) {}
""",
    os.path.join(SYNTAX_DIR, "SyntaxService.java"): """package com.swarmcoder.syntax;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public interface SyntaxService {
    ParseVerdict parse(Language lang, byte[] source);
    RepoMap repoMap(Path repoRoot, Set<String> includeGlobs);
    List<SymbolSig> signatures(Path file);
    String normalizeForClustering(Language lang, String diffHunkContext);
}
""",
    os.path.join(SYNTAX_DIR, "TreeSitterSyntaxService.java"): """package com.swarmcoder.syntax;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
// Assuming io.github.bonede API
import io.github.bonede.TreeSitter;
import io.github.bonede.TreeSitterJava;
import io.github.bonede.TreeSitterPython;
import io.github.bonede.TreeSitterHtml;
import io.github.bonede.TreeSitterCss;
import io.github.bonede.TreeSitterRust;
import io.github.bonede.TreeSitterTypescript;
import io.github.bonede.TreeSitterTsx;
import io.github.bonede.Parser;
import io.github.bonede.Tree;
import io.github.bonede.Node;
import io.github.bonede.Language;

public class TreeSitterSyntaxService implements SyntaxService {

    public TreeSitterSyntaxService() {
        // Initialization/loading native libs happens here
    }

    private Language getTSLanguage(com.swarmcoder.syntax.Language lang) {
        return switch (lang) {
            case JAVA -> TreeSitterJava.language();
            case PYTHON -> TreeSitterPython.language();
            case HTML -> TreeSitterHtml.language();
            case CSS -> TreeSitterCss.language();
            case RUST -> TreeSitterRust.language();
            case TYPESCRIPT -> TreeSitterTypescript.language();
            case TSX -> TreeSitterTsx.language();
            default -> TreeSitterJava.language(); // Fallback
        };
    }

    @Override
    public ParseVerdict parse(com.swarmcoder.syntax.Language lang, byte[] source) {
        try (Parser parser = new Parser()) {
            parser.setLanguage(getTSLanguage(lang));
            try (Tree tree = parser.parseString(new String(source))) {
                boolean hasError = tree.getRootNode().hasError();
                List<SyntaxError> errors = new ArrayList<>();
                if (hasError) {
                    errors.add(new SyntaxError(0, 0, 0, 0, "Syntax Error")); // Stubbed full traversal
                }
                return new ParseVerdict(!hasError, errors);
            }
        }
    }

    @Override
    public RepoMap repoMap(Path repoRoot, Set<String> includeGlobs) {
        return new RepoMap("Stub repo map content for M2");
    }

    @Override
    public List<SymbolSig> signatures(Path file) {
        return new ArrayList<>();
    }

    @Override
    public String normalizeForClustering(com.swarmcoder.syntax.Language lang, String diffHunkContext) {
        try (Parser parser = new Parser()) {
            parser.setLanguage(getTSLanguage(lang));
            try (Tree tree = parser.parseString(diffHunkContext)) {
                return normalizeNode(tree.getRootNode(), diffHunkContext);
            }
        }
    }
    
    private String normalizeNode(Node node, String source) {
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
"""
}

for filepath, content in models.items():
    with open(filepath, "w") as f:
        f.write(content)

print("Scaffolded sc-syntax.")
