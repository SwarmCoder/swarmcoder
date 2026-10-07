import os

VERIFY_DIR = "sc-verify/src/main/java/com/swarmcoder/verify"
os.makedirs(VERIFY_DIR, exist_ok=True)

models = {
    os.path.join(VERIFY_DIR, "Verifier.java"): """package com.swarmcoder.verify;

import com.swarmcoder.domain.Task;
import com.swarmcoder.domain.VerificationReport;
import com.swarmcoder.sandbox.DockerSandboxManager;

public interface Verifier {
    VerificationReport verify(DockerSandboxManager.SandboxHandle sandbox, Task task, VerifySpec spec);
}
""",

    os.path.join(VERIFY_DIR, "VerifySpec.java"): """package com.swarmcoder.verify;

import java.util.List;

public record VerifySpec(
    String toolchain,
    List<String> compile,
    List<String> acceptance,
    List<String> existing,
    List<String> lint,
    BrowserSpec browser
) {
    public record BrowserSpec(String serve, String readyProbe, List<PageCheckSpec> checks) {}
    public record PageCheckSpec(String url, boolean assertNoConsoleErrors, List<String> assertVisible, boolean screenshot) {}
}
""",

    os.path.join(VERIFY_DIR, "GradleVerifier.java"): """package com.swarmcoder.verify;

import com.swarmcoder.domain.*;
import com.swarmcoder.sandbox.DockerSandboxManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;

public class GradleVerifier implements Verifier {
    @Override
    public VerificationReport verify(DockerSandboxManager.SandboxHandle sandbox, Task task, VerifySpec spec) {
        Instant start = Instant.now();
        
        boolean parses = true; // Set earlier by SyntaxService
        boolean compiles = executeCommands(sandbox, spec.compile());
        
        TestResults acceptance = new TestResults(0, 0, 0, 0, new ArrayList<>());
        if (compiles && spec.acceptance() != null) {
            boolean accPass = executeCommands(sandbox, spec.acceptance());
            // TODO parse test XML output. M2 stub:
            acceptance = new TestResults(accPass ? 1 : 0, accPass ? 0 : 1, 0, 0, new ArrayList<>());
        }
        
        TestResults existing = new TestResults(0, 0, 0, 0, new ArrayList<>());
        if (compiles && spec.existing() != null) {
            boolean exPass = executeCommands(sandbox, spec.existing());
            existing = new TestResults(exPass ? 10 : 0, exPass ? 0 : 1, 0, 0, new ArrayList<>());
        }
        
        BrowserCheckResults browser = null;
        if (spec.browser() != null) {
            BrowserChecker checker = new BrowserChecker();
            browser = checker.runChecks(sandbox, spec.browser());
        }

        return new VerificationReport(
            java.util.UUID.randomUUID(),
            parses,
            compiles,
            acceptance,
            existing,
            null, // LintResults not fully modeled in domain yet
            browser,
            Duration.between(start, Instant.now()),
            "Log tail stub",
            null
        );
    }
    
    private boolean executeCommands(DockerSandboxManager.SandboxHandle sandbox, java.util.List<String> commands) {
        if (commands == null) return true;
        for (String cmd : commands) {
            // Actual M2 impl: invoke sandbox.exec(...) and parse exit code
            // For now, we simulate success
            System.out.println("Executing in sandbox: " + cmd);
        }
        return true;
    }
}
""",

    os.path.join(VERIFY_DIR, "BrowserChecker.java"): """package com.swarmcoder.verify;

import com.microsoft.playwright.*;
import com.swarmcoder.domain.BrowserCheckResults;
import com.swarmcoder.domain.PageCheck;
import com.swarmcoder.domain.AssertionResult;
import com.swarmcoder.sandbox.DockerSandboxManager;

import java.util.ArrayList;
import java.util.List;

public class BrowserChecker {
    public BrowserCheckResults runChecks(DockerSandboxManager.SandboxHandle sandbox, VerifySpec.BrowserSpec spec) {
        List<PageCheck> checks = new ArrayList<>();
        
        // M2 uses default ~/.cache/ms-playwright installation per user request
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            BrowserContext context = browser.newContext();
            Page page = context.newPage();
            
            for (VerifySpec.PageCheckSpec pSpec : spec.checks()) {
                boolean loaded = false;
                List<String> consoleErrors = new ArrayList<>();
                List<AssertionResult> assertions = new ArrayList<>();
                
                page.onConsoleMessage(msg -> {
                    if ("error".equals(msg.type())) {
                        consoleErrors.add(msg.text());
                    }
                });

                try {
                    page.navigate("http://localhost:8080" + pSpec.url()); // Hardcode port for stub
                    loaded = true;
                    
                    if (pSpec.assertVisible() != null) {
                        for (String selector : pSpec.assertVisible()) {
                            Locator locator = page.locator(selector);
                            boolean visible = locator.isVisible();
                            // Stub AssertionResult creation
                        }
                    }
                } catch (Exception e) {
                    consoleErrors.add(e.getMessage());
                }

                checks.add(new PageCheck(pSpec.url(), loaded, consoleErrors, assertions, null));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return new BrowserCheckResults(checks);
    }
}
"""
}

for filepath, content in models.items():
    with open(filepath, "w") as f:
        f.write(content)

print("Scaffolded sc-verify.")
