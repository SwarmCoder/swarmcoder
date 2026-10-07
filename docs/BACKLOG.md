# Backlog

Parked work, newest first. Each item says what it is, why it matters, and roughly what it costs.

## Agent-driven browser checks (parked 2026-09-30)

**What:** let SwarmCoder prove a web page works, not just that it compiles.

**Why:** browser-only code (TeaVM client modules) is today proved by compiling alone. A story's
user-facing half can be delivered broken. HamBook is mostly screens, so this gap grows with it.

**Already there:** `sc-verify` `BrowserVerifier` drives headless Chromium via Playwright: serves the
candidate's app, loads pages, runs predeclared selector assertions, collects console errors,
stores screenshots.

**Missing:**
1. Browser tools for agents: navigate, click, fill, read the page as an accessibility tree,
   screenshot. Same tool shape workers already use.
2. Browser acceptance tests as the gate for UI checks: the test author writes Playwright tests
   from the story's checks; they gate delivery like JVM acceptance tests do.
3. A per-project serve command (for ZeroZ: TeaVM build, then start the server).
4. Optional: an agentic walker that exercises each check on the live page at story end and
   reports — a diagnostic for the judge, not the gate.

**Vision model:** not needed for 1–3; the accessibility tree covers presence, text and errors.
Screenshots plus a vision model only add layout/visual checks later (a cloud vision model for that
one role would do; the Spark's DeepSeek V4 Flash is text-only).

**Size:** one or two worker jobs for 1–3.
