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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ReducedMotion;
import com.microsoft.playwright.options.ScreenshotAnimations;
import com.swarmcoder.domain.AssertionResult;
import com.swarmcoder.domain.BrowserCheckResults;
import com.swarmcoder.domain.BrowserStageOutcome;
import com.swarmcoder.domain.PageCheck;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Browser-level verification (spec §10.3): Playwright headless Chromium running on the
 * workstation against the candidate's served app. Deterministic viewport (1440×900),
 * animations disabled, network-idle wait, console errors and visibility assertions collected,
 * screenshots stored via {@link BlobSink}.
 *
 * <p>Two serve modes: a {@code serve} shell command with a {@code {PORT}} placeholder
 * (requires an ExecTarget that can both start a background service AND let this process reach
 * it — the local target today), or {@code static:<dir>} which serves workspace files through a
 * throwaway HTTP server that reads via {@link ExecTarget#readFile} — this mode works against any
 * target, including sandboxes, with no background exec needed.
 *
 * <p><b>Sandboxes can now start the service; this stage still cannot look at it.</b>
 * {@link SandboxExecTarget} runs background services properly, so "the app came up" is a fact a
 * sandbox can establish. What it cannot do is show that app to the browser <i>here</i>: the
 * container publishes no ports, and this class drives Chromium in the orchestrator's own process.
 * Serve-mode against such a target is therefore refused up front via
 * {@link ExecTarget#hostCannotReachServices()} — refused, not attempted and failed, because
 * "nothing answered on localhost" would otherwise be recorded as "the candidate's application
 * does not start". Closing that gap means running the page checks inside the container too; the
 * UI sandbox image ({@code sc-sandbox/images/sc-java-ui}) carries the browser and the checker
 * script for it, and switching this stage over is a deliberate decision about cost, not a
 * missing capability.
 *
 * <p>The console-error assertion is folded into the page's {@link AssertionResult} list
 * (selector {@code console}), so {@link Verdicts} only needs "loaded and all assertions
 * passed" to judge browser cleanliness.
 *
 * <h2>"The application failed to start" versus "the harness could not try"</h2>
 *
 * <p>Every failure here used to look the same: the page list came back with every page not loaded
 * and one {@code infrastructure} assertion on each, and the candidate died. That is the correct
 * answer to exactly one of the things that can go wrong — the candidate's application refused to
 * come up — and the wrong answer to all the others. No headless browser on this machine, an
 * execution target that cannot start a background process at all, a target whose services this
 * process cannot reach (any sandbox under {@code network: none}), a fixed port already held by
 * something else: in each of those the harness learned nothing at all about the candidate, and
 * failing it would park every run for a reason that has nothing to do with the code.
 *
 * <p>The line drawn is the one {@code EndpointOutage} draws for model endpoints, and it is drawn
 * the same way round. <b>Transport-shaped failures are not verdicts</b>: they are tagged
 * {@link BrowserStageOutcome#COULD_NOT_TRY} and {@link Verdicts} ignores them. <b>Everything else
 * defaults to a real failure</b>, including "the serve command exited" and "nothing ever answered
 * the readiness probe", because those are precisely the evidence that the application does not run
 * — the whole reason this stage exists. A misclassification therefore falls towards the strict
 * side, which is the only safe direction for a gate.
 */
public final class BrowserVerifier {

    private static final Logger log = LoggerFactory.getLogger(BrowserVerifier.class);
    private static final String STATIC_PREFIX = "static:";
    private static final int NAVIGATION_TIMEOUT_MS = 30_000;

    private final BlobSink blobSink;

    public BrowserVerifier(BlobSink blobSink) {
        this.blobSink = blobSink == null ? BlobSink.NONE : blobSink;
    }

    public BrowserCheckResults run(ExecTarget target, VerifySpec.BrowserSpec spec, StringBuilder fullLog) {
        if (target.browserChecksRunInside() && spec.serve() != null) {
            // A served application and built static pages alike: the pages are model-written code
            // too, so a browser looks at them inside the container, never on this PC.
            return runInside(target, spec, fullLog);
        }
        if (spec.serve() != null && spec.serve().startsWith(STATIC_PREFIX)
                && target.hostCannotReachServices().isPresent()) {
            // A container that was not started for browser checks: rendering its pages here would
            // put model-written page script in this PC's browser, so the stage declines instead.
            return couldNotTry(spec, fullLog, "the pages would have to be rendered by a browser on "
                + "this PC, and page script a model wrote is not run there. Browser checks run "
                + "inside the container of final integration and story delivery, which carries the "
                + "browser; this candidate's own container does not. Nothing was run on this PC "
                + "instead");
        }
        if (anyJourney(spec)) {
            // Steps are carried out by the checker inside the container only. Loading the pages
            // here and skipping the steps would report a journey nobody made.
            return couldNotTry(spec, fullLog, "the contract's browser check is a journey (it has "
                + "steps), and journeys are made by the browser inside the container of final "
                + "integration and story delivery. This tree was not checked in one, so the "
                + "journey was not made. Nothing was run on this PC instead");
        }
        int port;
        if (spec.hasFixedPort()) {
            port = spec.port();
            if (!isPortFree(port)) {
                // Somebody else is on it: another verification on this machine, or the operator's
                // own copy of the app. Starting here would either fail to bind or — far worse —
                // drive the browser at somebody else's application and report on that.
                return couldNotTry(spec, fullLog, "port " + port + " is already in use on this "
                    + "machine, so the candidate's application could not be started there. Nothing "
                    + "was learned about this candidate. (The contract names a fixed port because "
                    + "this application chooses its own; two verifications cannot share it.)");
            }
        } else {
            port = findFreePort();
        }
        String baseUrl = "http://localhost:" + port;

        if (spec.serve() != null && spec.serve().startsWith(STATIC_PREFIX)) {
            String dir = spec.serve().substring(STATIC_PREFIX.length()).trim();
            appendLog(fullLog, "[browser] serving workspace dir '" + dir + "' statically on port " + port);
            HttpServer server = null;
            try {
                server = startStaticServer(target, dir, port);
                return checkPages(spec, baseUrl, fullLog);
            } catch (IOException e) {
                // The throwaway server is the harness's own, not the candidate's.
                return couldNotTry(spec, fullLog, "the harness's own static file server could not "
                    + "start: " + e.getMessage());
            } finally {
                if (server != null) {
                    server.stop(0);
                }
            }
        }

        // Ask BEFORE launching anything. On a target whose services this process cannot reach,
        // starting the application and then failing to connect produces the exact evidence that
        // means "this candidate's application does not run" — from a cause that is entirely the
        // harness's. The candidate must not pay for that, so the stage declines instead.
        Optional<String> unreachable = target.hostCannotReachServices();
        if (unreachable.isPresent()) {
            return couldNotTry(spec, fullLog, unreachable.get()
                + ". A 'serve' browser check needs a target this process can open over HTTP; "
                + "'static:<dir>' checks work here and are unaffected");
        }

        String serveCommand = spec.serve().replace("{PORT}", String.valueOf(port));
        appendLog(fullLog, "[browser] $ " + serveCommand);
        try (ExecTarget.ServiceHandle service = target.startService(serveCommand)) {
            String probeUrl = spec.readyProbe() == null
                ? baseUrl + "/"
                : spec.readyProbe().replace("{PORT}", String.valueOf(port));
            if (!awaitReady(service, probeUrl, spec.effectiveReadyTimeoutSeconds(), fullLog)) {
                String tail = tail(service.outputSoFar(), 40);
                appendLog(fullLog, "[browser] FAILED — the application never became ready; "
                    + "output tail:\n" + tail);
                // A real verdict. The candidate's application was launched and did not answer.
                return failedToStart(spec, "the application did not start: nothing answered "
                    + probeUrl + " within " + spec.effectiveReadyTimeoutSeconds() + " seconds");
            }
            return checkPages(spec, baseUrl, fullLog);
        } catch (UnsupportedOperationException e) {
            // This execution target has no background exec at all — a property of the harness, not
            // of the candidate. Every sandbox target is in this state today.
            return couldNotTry(spec, fullLog, e.getMessage());
        } catch (IOException e) {
            // The command could not be launched as a process at all (interpreter missing, target
            // unreachable). Nothing of the candidate ran, so nothing about it was established.
            return couldNotTry(spec, fullLog, "the serve command could not be launched on this "
                + "machine: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- inside the container

    private static final String CHECKER = "\"${SC_BROWSER_CHECK:-/opt/sc-browser/check.js}\"";
    private static final String JSON_BEGIN = "<<<SC-BROWSER-JSON";
    private static final String JSON_END = "SC-BROWSER-JSON>>>";
    /** The port an application is told to use in a container when the contract names none. */
    private static final int DEFAULT_PORT_INSIDE = 18080;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * The whole browser stage inside the target's container: the application is started there, a
     * browser in the same container opens it on the container's own loopback, and the result -
     * screenshots included - comes back over the exec channel. Nothing of it runs on this PC and
     * the container still has no network (final integration and story delivery, 2026-10-02).
     *
     * <p>It needs the UI image ({@code sc-sandbox/images/sc-java-ui}), which is the worker image
     * plus a browser and the checker script. In any other image the stage reports that it could
     * not run and names the command that builds it. It never falls back to this PC.
     */
    private BrowserCheckResults runInside(ExecTarget target, VerifySpec.BrowserSpec spec,
                                          StringBuilder fullLog) {
        String uiImage = com.swarmcoder.sandbox.DockerSandboxManager.uiImage();
        try {
            ExecResult has = target.exec("test -f " + CHECKER
                + " && command -v node >/dev/null 2>&1 && command -v curl >/dev/null 2>&1"
                + " && echo SC_BROWSER_PRESENT", 30);
            if (!has.output().contains("SC_BROWSER_PRESENT")) {
                return couldNotTry(spec, fullLog, "the container this tree was checked in has no "
                    + "browser in it. Browser checks run inside the container, because the "
                    + "application is code a model wrote and may not be started on this PC. What "
                    + "is missing is the image `" + uiImage + "`. Build it once, in the "
                    + "SwarmCoder folder: `docker build -t " + uiImage
                    + " sc-sandbox/images/sc-java-ui`. Nothing was run on this PC instead");
            }
        } catch (IOException | RuntimeException e) {
            return couldNotTry(spec, fullLog, "the container could not be asked whether it has a "
                + "browser: " + e.getMessage());
        }

        boolean pages = spec.serve().startsWith(STATIC_PREFIX);
        int port = !pages && spec.hasFixedPort() ? spec.port() : DEFAULT_PORT_INSIDE;
        String baseUrl = "http://127.0.0.1:" + port;
        String serveCommand;
        if (pages) {
            String dir = spec.serve().substring(STATIC_PREFIX.length()).trim();
            serveCommand = staticServerCommand(dir, port);
            appendLog(fullLog, "[browser] (inside the container) serving workspace dir '"
                + (dir.isEmpty() ? "." : dir) + "' statically on port " + port);
        } else {
            serveCommand = spec.serve().replace("{PORT}", String.valueOf(port));
            appendLog(fullLog, "[browser] (inside the container) $ " + serveCommand);
        }
        try (ExecTarget.ServiceHandle service = target.startService(serveCommand)) {
            String probeUrl = pages ? baseUrl + STATIC_READY_PATH
                : spec.readyProbe() == null
                    ? baseUrl + "/"
                    : spec.readyProbe().replace("{PORT}", String.valueOf(port));
            if (!awaitReadyInside(target, service, probeUrl, spec.effectiveReadyTimeoutSeconds(),
                    fullLog)) {
                appendLog(fullLog, "[browser] FAILED - the application never became ready; "
                    + "output tail:\n" + tail(service.outputSoFar(), 40));
                return failedToStart(spec, "the application did not start: nothing answered "
                    + probeUrl + " inside the container within "
                    + spec.effectiveReadyTimeoutSeconds() + " seconds");
            }
            return checkPagesInside(target, spec, baseUrl, fullLog);
        } catch (UnsupportedOperationException e) {
            return couldNotTry(spec, fullLog, e.getMessage());
        } catch (IOException | RuntimeException e) {
            return couldNotTry(spec, fullLog, "the serve command could not be started in the "
                + "container: " + e.getMessage());
        }
    }

    /** Answers 200 from the in-container static server, so a folder with no index.html is "up". */
    static final String STATIC_READY_PATH = "/__sc_ready";

    /** The static file server's source: node, which the UI image carries. {@code DIR}, {@code PORT}. */
    private static final String STATIC_SERVER_JS = """
        const http=require('http'),fs=require('fs'),path=require('path');
        const root=path.resolve(process.cwd(),DIR);
        const types={'.html':'text/html; charset=utf-8','.htm':'text/html; charset=utf-8',
          '.css':'text/css; charset=utf-8','.js':'text/javascript; charset=utf-8',
          '.mjs':'text/javascript; charset=utf-8','.json':'application/json','.svg':'image/svg+xml',
          '.png':'image/png','.jpg':'image/jpeg','.jpeg':'image/jpeg','.gif':'image/gif',
          '.ico':'image/x-icon','.woff':'font/woff','.woff2':'font/woff2','.wasm':'application/wasm',
          '.map':'application/json','.txt':'text/plain; charset=utf-8'};
        http.createServer((q,s)=>{
          let p;
          try{p=decodeURIComponent(new URL(q.url,'http://x').pathname)}catch(e){s.statusCode=400;return s.end()}
          if(p==='__READY__'){s.statusCode=200;return s.end('ok')}
          if(p.endsWith('/'))p+='index.html';
          const f=path.join(root,p);
          if(f!==root&&!f.startsWith(root+path.sep)){s.statusCode=403;return s.end()}
          fs.readFile(f,(e,d)=>{
            if(e){s.statusCode=404;return s.end()}
            s.setHeader('content-type',types[path.extname(f).toLowerCase()]||'application/octet-stream');
            s.end(d)})
        }).listen(PORT,'127.0.0.1');
        """;

    /**
     * The command that serves a workspace folder inside the container: a node file server bound to
     * the container's own loopback. The script travels base64-encoded, so the folder name cannot
     * break the quoting.
     */
    static String staticServerCommand(String dir, int port) {
        String folder = dir == null || dir.isBlank() ? "." : dir;
        String js = STATIC_SERVER_JS
            .replace("__READY__", STATIC_READY_PATH)
            .replace("DIR", JSON.valueToTree(folder).toString())
            .replace("PORT", String.valueOf(port));
        String encoded = java.util.Base64.getEncoder()
            .encodeToString(js.getBytes(StandardCharsets.UTF_8));
        return "F=$(mktemp) && printf %s '" + encoded + "' | base64 -d > \"$F\" && exec node \"$F\"";
    }

    private static boolean awaitReadyInside(ExecTarget target, ExecTarget.ServiceHandle service,
                                            String probeUrl, int timeoutSeconds,
                                            StringBuilder fullLog) throws IOException {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        while (Instant.now().isBefore(deadline)) {
            if (!service.isAlive()) {
                appendLog(fullLog, "[browser] serve command exited before becoming ready");
                return false;
            }
            ExecResult probe = target.exec("curl -fsS -o /dev/null --max-time 2 "
                + shellQuote(probeUrl) + " && echo SC_READY", 30);
            if (probe.output().contains("SC_READY")) {
                return true;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private BrowserCheckResults checkPagesInside(ExecTarget target, VerifySpec.BrowserSpec spec,
                                                 String baseUrl, StringBuilder fullLog)
            throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode request = JSON.createObjectNode();
        request.put("baseUrl", baseUrl);
        request.put("navigationTimeoutMs", NAVIGATION_TIMEOUT_MS);
        request.putObject("viewport").put("width", 1440).put("height", 900);
        com.fasterxml.jackson.databind.node.ArrayNode checks = request.putArray("checks");
        List<VerifySpec.PageCheckSpec> pages =
            spec.checks() == null ? List.of() : spec.checks();
        for (VerifySpec.PageCheckSpec page : pages) {
            com.fasterxml.jackson.databind.node.ObjectNode check = checks.addObject();
            check.put("url", page.url());
            check.put("assertNoConsoleErrors", page.assertNoConsoleErrors());
            check.put("screenshot", page.screenshot());
            com.fasterxml.jackson.databind.node.ArrayNode visible = check.putArray("assertVisible");
            if (page.assertVisible() != null) {
                page.assertVisible().forEach(visible::add);
            }
            if (page.isJourney()) {
                com.fasterxml.jackson.databind.node.ArrayNode steps = check.putArray("steps");
                for (VerifySpec.StepSpec step : page.steps()) {
                    com.fasterxml.jackson.databind.node.ObjectNode one = steps.addObject();
                    one.put("label", step.describe());
                    putIfSet(one, "click", step.click());
                    putIfSet(one, "fill", step.fill());
                    putIfSet(one, "value", step.value());
                    putIfSet(one, "press", step.press());
                    putIfSet(one, "expectVisible", step.expectVisible());
                    putIfSet(one, "expectHidden", step.expectHidden());
                }
            }
        }
        String encoded = java.util.Base64.getEncoder()
            .encodeToString(JSON.writeValueAsBytes(request));
        // The checker is sent with the request, so the script that runs is this build's and an
        // image built before journeys existed needs no rebuild. The image supplies node, the
        // browser driver and the browser; nothing is fetched.
        String checker = CHECKER;
        String place = "";
        String script = checkerScript();
        if (script != null) {
            checker = "/tmp/sc-browser-check.js";
            place = "printf %s '" + java.util.Base64.getEncoder().encodeToString(
                script.getBytes(StandardCharsets.UTF_8)) + "' | base64 -d > " + checker + " && ";
        }
        ExecResult run = target.exec(place + "printf %s '" + encoded + "' | base64 -d | node "
            + checker, 120 + 60 * Math.max(1, pages.size()));
        String output = run.output() == null ? "" : run.output();
        int from = output.indexOf(JSON_BEGIN);
        int to = output.indexOf(JSON_END);
        if (from < 0 || to < from) {
            return couldNotTry(spec, fullLog, "the browser inside the container gave no result "
                + "(exit " + run.exitCode() + (run.timedOut() ? ", timed out" : "") + "): "
                + tail(output, 15));
        }
        com.fasterxml.jackson.databind.JsonNode result =
            JSON.readTree(output.substring(from + JSON_BEGIN.length(), to).trim());
        if (!result.path("ok").asBoolean()) {
            // The checker itself fell over (no Chromium, a driver that will not launch): the
            // application may be perfectly fine, so this is not a verdict on it.
            return couldNotTry(spec, fullLog, "the browser inside the container could not be "
                + "driven: " + tail(result.path("error").asText(), 15));
        }
        List<PageCheck> checked = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode page : result.path("pages")) {
            List<String> consoleErrors = new ArrayList<>();
            page.path("consoleErrors").forEach(e -> consoleErrors.add(e.asText()));
            List<AssertionResult> assertions = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode a : page.path("assertions")) {
                assertions.add(new AssertionResult(a.path("selector").asText(),
                    a.path("passed").asBoolean(), a.path("message").asText()));
            }
            String screenshotRef = null;
            String png = page.path("screenshotBase64").asText(null);
            if (png != null && !png.isBlank() && !"null".equals(png)) {
                screenshotRef = blobSink.put(java.util.Base64.getDecoder().decode(png));
            }
            boolean loaded = page.path("loaded").asBoolean();
            String url = page.path("url").asText();
            appendLog(fullLog, "[browser] " + url + " loaded=" + loaded
                + " consoleErrors=" + consoleErrors.size()
                + " assertions=" + assertions.stream().filter(AssertionResult::passed).count()
                + "/" + assertions.size() + " passed (inside the container)");
            checked.add(new PageCheck(url, loaded, consoleErrors, assertions, screenshotRef));
        }
        return BrowserCheckResults.executed(checked);
    }

    private static void putIfSet(com.fasterxml.jackson.databind.node.ObjectNode node,
                                 String name, String value) {
        if (value != null) {
            node.put(name, value);
        }
    }

    /**
     * This build's page checker (the file the UI image is also built with), or null when it is
     * not on the classpath and the image's own copy is used.
     */
    static String checkerScript() {
        try (java.io.InputStream in = com.swarmcoder.sandbox.DockerSandboxManager.class
                .getResourceAsStream("/sc-browser/check.js")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean anyJourney(VerifySpec.BrowserSpec spec) {
        return spec.checks() != null
            && spec.checks().stream().anyMatch(VerifySpec.PageCheckSpec::isJourney);
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private BrowserCheckResults checkPages(VerifySpec.BrowserSpec spec, String baseUrl, StringBuilder fullLog) {
        List<PageCheck> checks = new ArrayList<>();
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            try (var context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1440, 900)
                    .setReducedMotion(ReducedMotion.REDUCE))) {
                for (VerifySpec.PageCheckSpec pageSpec : spec.checks()) {
                    checks.add(checkPage(context.newPage(), pageSpec, baseUrl, fullLog));
                }
            }
        } catch (Exception e) {
            // Playwright itself failed: no browser installed, a driver that will not launch. The
            // application may well be running perfectly; there is simply nothing to look at it
            // with.
            log.error("Playwright failure: {}", e.getMessage());
            return couldNotTry(spec, fullLog,
                "no headless browser could be driven on this machine: " + e.getMessage()
                + ". Install Playwright's Chromium (`playwright install chromium`) to switch this "
                + "check on; until then the browser stage proves nothing either way.");
        }
        return BrowserCheckResults.executed(checks);
    }

    private PageCheck checkPage(Page page, VerifySpec.PageCheckSpec spec, String baseUrl, StringBuilder fullLog) {
        List<String> consoleErrors = new CopyOnWriteArrayList<>();
        List<AssertionResult> assertions = new ArrayList<>();
        boolean loaded = false;
        String screenshotRef = null;

        page.onConsoleMessage(msg -> {
            if ("error".equals(msg.type())) {
                consoleErrors.add(msg.text());
            }
        });
        page.onPageError(consoleErrors::add);

        String url = baseUrl + spec.url();
        try {
            page.navigate(url, new Page.NavigateOptions().setTimeout(NAVIGATION_TIMEOUT_MS));
            page.waitForLoadState(LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(NAVIGATION_TIMEOUT_MS));
            loaded = true;

            if (spec.assertVisible() != null) {
                for (String selector : spec.assertVisible()) {
                    boolean visible;
                    String message;
                    try {
                        Locator locator = page.locator(selector).first();
                        visible = locator.isVisible();
                        message = visible ? "visible" : "not visible";
                    } catch (Exception e) {
                        visible = false;
                        message = "selector error: " + e.getMessage();
                    }
                    assertions.add(new AssertionResult(selector, visible, message));
                }
            }
            if (spec.assertNoConsoleErrors()) {
                assertions.add(new AssertionResult("console", consoleErrors.isEmpty(),
                    consoleErrors.isEmpty() ? "no console errors" : consoleErrors.size() + " console error(s)"));
            }
            if (spec.screenshot()) {
                byte[] png = page.screenshot(new Page.ScreenshotOptions()
                    .setFullPage(true)
                    .setAnimations(ScreenshotAnimations.DISABLED));
                screenshotRef = blobSink.put(png);
            }
        } catch (Exception e) {
            appendLog(fullLog, "[browser] " + spec.url() + " failed: " + e.getMessage());
            assertions.add(new AssertionResult("page-load", false, e.getMessage()));
        } finally {
            page.close();
        }

        appendLog(fullLog, "[browser] " + spec.url() + " loaded=" + loaded
            + " consoleErrors=" + consoleErrors.size()
            + " assertions=" + assertions.stream().filter(AssertionResult::passed).count()
            + "/" + assertions.size() + " passed");
        return new PageCheck(spec.url(), loaded, new ArrayList<>(consoleErrors), assertions, screenshotRef);
    }

    /** Serves workspace files through the ExecTarget so static checks work against any target. */
    private static HttpServer startStaticServer(ExecTarget target, String dir, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        String root = dir.isEmpty() || dir.equals(".") ? "" : (dir.endsWith("/") ? dir : dir + "/");
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/")) {
                path = path + "index.html";
            }
            String relative = root + (path.startsWith("/") ? path.substring(1) : path);
            String content;
            try {
                content = target.readFile(relative, 8 * 1024 * 1024);
            } catch (IOException e) {
                content = null;
            }
            if (content == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType(relative));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.setExecutor(null);
        server.start();
        return server;
    }

    private boolean awaitReady(ExecTarget.ServiceHandle service, String probeUrl,
                               int timeoutSeconds, StringBuilder fullLog) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        while (Instant.now().isBefore(deadline)) {
            if (!service.isAlive()) {
                appendLog(fullLog, "[browser] serve command exited before becoming ready");
                return false;
            }
            try {
                HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create(probeUrl)).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return true;
                }
            } catch (IOException e) {
                // Not up yet.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * A real failure: the candidate's application was launched and never served anything. Every
     * configured page is marked not loaded, and the stage is EXECUTED — this kills the candidate,
     * which is the point of the whole stage.
     */
    private static BrowserCheckResults failedToStart(VerifySpec.BrowserSpec spec, String reason) {
        return BrowserCheckResults.executed(allPagesFailed(spec, "did-not-start", reason));
    }

    /**
     * Not a verdict: the harness never got as far as trying, so nothing at all was learned. The
     * page list is still filled in so the report shows what WOULD have been checked, but the stage
     * outcome tells {@link Verdicts} to ignore it.
     */
    private static BrowserCheckResults couldNotTry(VerifySpec.BrowserSpec spec, StringBuilder fullLog,
                                                   String reason) {
        appendLog(fullLog, "[browser] NOT RUN — " + reason
            + "\n[browser] This is not evidence about the candidate, and it does not fail it.");
        log.warn("Browser stage could not be attempted: {}", reason);
        return BrowserCheckResults.couldNotTry(allPagesFailed(spec, "not-attempted", reason), reason);
    }

    private static List<PageCheck> allPagesFailed(VerifySpec.BrowserSpec spec, String selector,
                                                  String reason) {
        List<PageCheck> checks = new ArrayList<>();
        for (VerifySpec.PageCheckSpec pageSpec : spec.checks() == null ? List.<VerifySpec.PageCheckSpec>of() : spec.checks()) {
            checks.add(new PageCheck(pageSpec.url(), false, List.of(),
                List.of(new AssertionResult(selector, false, reason)), null));
        }
        return checks;
    }

    /** Whether nothing is currently listening on this machine's given port. */
    private static boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return socket.getLocalPort() == port;
        } catch (IOException e) {
            return false;
        }
    }

    private static String contentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "text/plain; charset=utf-8";
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("No free port available", e);
        }
    }

    private static void appendLog(StringBuilder fullLog, String line) {
        fullLog.append(line).append('\n');
    }

    private static String tail(String text, int lines) {
        String[] all = text.split("\n", -1);
        if (all.length <= lines) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = all.length - lines; i < all.length; i++) {
            sb.append(all[i]).append('\n');
        }
        return sb.toString();
    }
}
