# Which tests run, when, and how you know what did not

**Written 2026-08-28, when the gates were changed.**

Before this, thirteen test classes and four further test methods ran only if somebody typed a
flag, and nobody ever did. Among them were the six tests that boot the real console in a real
browser — the only thing that had ever driven the operator interface. They read as coverage in
every report and had not been run in an ordinary build for weeks.

That is fixed by inverting the question. A test no longer asks "did somebody switch me on?" It
asks "is what I need here?" — and if it is, it runs.

---

## The rule

Every test that needs something from outside the build declares it:

```java
@Test
@RunsWhen(Need.CHROMIUM)
void theConsoleBootsInARealBrowser() { ... }
```

`@RunsWhen` lives in `sc-testsupport`, which every module with such a test depends on at test
scope. There are six needs and they fall into two groups.

### Present or absent — no flag, ever

| Need | Satisfied when | Skipped when |
|---|---|---|
| `CHROMIUM` | Playwright's Chromium is in its browser store | it is not installed |
| `DOCKER` | a Docker daemon answers **and** the `swarmcoder-worker` image exists | either is missing |
| `MAVEN` | `mvn` is on the PATH | it is not |

These run in an ordinary `mvn test` on any machine that has the tool. Nobody opts in, so nobody
can forget to.

### Deliberately off, each for a stated reason

| Need | Switched on by | Why it stays off |
|---|---|---|
| `LIVE_MODEL` | `-Dswarmcoder.live.baseUrl=<url>` | the model server is shared with other work and is not always up. A build that depends on it fails for reasons that have nothing to do with the code, and quietly loads somebody else's hardware |
| `PAID_CLOUD_MODELS` | `SWARMCODER_CONFIG_E2E=true` | it spends real money on every run. A build must not be able to bill anybody |
| `DEMO_REPO` | `-Dswarmcoder.demo.repo=<path>` | the `dev/demo-repo` it wants is not in this checkout, so there is nothing to point it at |

---

## Why the polarity matters more than it looks

There is a trap recorded in this repository: **an empty `<properties>` block in a pom silently
overrides a command-line `-D` inside the surefire fork**, and it had already made one gate skip
without anybody noticing. That is why two of the old gates used environment variables instead.

The trap is real, and the environment variable was the wrong answer to it — a variable nobody sets
skips exactly as silently as a property that got lost. The fix is the direction of the default:

- **Before:** the flag failing to arrive meant **the test silently did not run.**
- **Now:** the property failing to arrive means **the test runs.**

The plumbing can still break. It can no longer cost coverage. The same reasoning governs the tool
probes in `Tools`: a probe that is not sure says the tool is there, so an uncertain probe produces
a test that runs and fails with the real error, never a test that quietly disappears.

---

## How a green build tells you what it did not check

Two places, both written by `NotRun`:

1. **The build output.** Every skip prints a banner on standard error, which surefire pipes to the
   Maven console:

   ```
     !!  NOT RUN  LiveEndToEndTest#greenfieldRunReachesApprovalWithVerifiedIntegration
     !!    no live model endpoint was named, and this stays off by default on purpose: the model
           server is shared with other work and a build must not depend on it. Run it deliberately
           with -Dswarmcoder.live.baseUrl=<url>.
   ```

2. **`<module>/target/tests-not-run.txt`.** The same lines, timestamped, appended by every fork.
   After a build it is the complete list of what was not checked, in one file. `mvn clean` wipes
   it, so it cannot outlive the tree it describes.

Never use `Assumptions.assumeTrue` to skip. It skips in silence, which is the fault this whole
arrangement exists to remove. Use `NotRun.needed(...)` — same effect, announced.

---

## What it costs

Measured on this machine, 2026-08-28, by running the same command twice — once ordinarily and once
with everything switched off.

| Module | Tests now running that did not before | Added |
|---|---|---|
| `sc-console` | six browser tests, one per forked JVM | **+3 min 30 s** (5:39 against 2:09) |
| `sc-app` | the delivery journey with candidates verified in Docker | **+1 min 38 s** |
| `sc-sandbox` | the live container-hardening checks | **+34 s** |
| `sc-verify` | the browser page-check verifier | **+20 s** |
| `sc-workflow` | the real Maven run that pins how test ids are spelt | **+8 s** |
| | | **about 6 minutes in total** |

### Why this is not behind a profile

The obvious answer to six minutes is a profile that continuous integration runs and a developer
can invoke by hand. **There is no continuous integration in this repository** — no
`.github/workflows`, no pipeline of any kind, nothing that builds this code except a person at
this workstation. A profile CI runs would run nowhere at all, which is the state we just came
from under a different name.

So they run in the ordinary build. Six minutes buys the only automatic check that exists on
sixteen thousand lines of screen code, on whether a worker is really confined to its container,
and on whether the test runner still spells test ids the way the product's traceability claim
depends on. Set against a full suite that already runs for tens of minutes, that is a small
fraction; set against nothing, it is everything.

### The escape hatch, for a tight loop only

```
mvn -o -pl sc-console -am test -Dswarmcoder.skipSlowTests=true
```

or `SWARMCODER_SKIP_SLOW_TESTS=true` in the environment. It skips every `@RunsWhen` test — and
announces every one of them, in both places above, so a build run this way cannot be mistaken for
a build that checked everything.

---

## Two standing constraints, neither of them negotiable

**One browser test per JVM.** `sc-console/pom.xml` sets `reuseForks=false`. The framework's shared
signals bind to whichever server engine started first in a process, so a second console started in
the same JVM serves pages whose updates never arrive, and the test asserts nothing while passing.
This was re-measured against ZeroZ Stack 0.7.0 and confirmed; the pom carries the record. It is
why there are six browser test classes with one test each, and why each new browser test costs a
whole forked JVM.

**Never run a bare `mvn test`.** The full reactor suite is tens of minutes and an interrupted run
yields no verdict at all. Name what you are running: `-pl <module> -am -Dtest=<Class>`.
