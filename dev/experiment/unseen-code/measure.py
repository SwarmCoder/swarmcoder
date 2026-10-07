#!/usr/bin/env python3
"""
Measure what a plain-loop run actually produced.

Run after plain_loop.py has finished (or been stopped).  Runs the builds itself
from the repository the model left behind, so the verdict does not depend on
anything the model said.

    python measure.py --run 1

Writes `<scratch>/plain-loop/run-<n>/result.md`.
"""

import argparse
import json
import os
import re
import subprocess

SCRATCH = os.path.join(os.path.expanduser("~"), ".swarmcoder", "experiment", "unseen-code").replace("\\", "/")

FORBIDDEN = {
    "Spring": r"org\.springframework|spring-boot|@SpringBootApplication",
    "JPA/Hibernate": r"jakarta\.persistence|javax\.persistence|hibernate|@Entity\b",
    "REST/HTTP controllers": r"jakarta\.ws\.rs|@Path\b|@GET\b|@POST\b|RestController",
    "JSON": r"com\.fasterxml\.jackson|jakarta\.json|org\.json|Gson",
    "JavaScript/TypeScript sources": None,          # handled by file extension
    "Vaadin": r"com\.vaadin",
}


def sh(cmd, cwd, timeout=900):
    p = subprocess.run(cmd, shell=True, cwd=cwd, capture_output=True,
                       text=True, encoding="utf-8", errors="replace",
                       timeout=timeout)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def first_error(text):
    for line in text.splitlines():
        if "ERROR" in line or "error:" in line:
            return line.strip()
    return ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--variant", required=True)
    ap.add_argument("--control", action="store_true")
    opts = ap.parse_args()

    run_dir = os.path.join(SCRATCH, opts.variant)
    repo = os.path.join(run_dir, "repo")
    with open(os.path.join(run_dir, "stats.json"), encoding="utf-8") as fh:
        stats = json.load(fh)

    label = "Variant " + opts.variant
    out = [f"# {label} — measurement\n"]
    if opts.control:
        out.append("Plain Java, single Maven module, no framework, no "
                   "documentation folder. Same harness, same model, same "
                   "endpoint as the ZeroZ Stack runs. Caps: 120 turns, 2 hours.\n")
    out.append(f"Repository: `{repo}` cloned at `{stats['head']}`\n")
    out.append(f"Model `{stats['model']}` at the free local endpoint, temperature 0.2.\n")
    out.append(f"**Thinking mode: {stats.get('thinking', 'on').upper()}** — "
               f"{stats.get('reasoning_turns', 0)} of {stats['turns']} turns came back "
               f"with reasoning, {stats.get('reasoning_chars', 0)} chars / "
               f"{stats.get('reasoning_tokens', 0)} reasoning tokens in total.\n")

    # ---- run behaviour -------------------------------------------------
    out.append("\n## What the run did\n")
    out.append(f"- Stopped because: **{stats['stop_reason']}**")
    out.append(f"- Turns: **{stats['turns']}** "
               f"(cap {stats.get('turn_cap', 200)})")
    out.append(f"- Wall time: **{stats['wall_seconds'] / 60:.0f} min** "
               f"(cap {stats.get('wall_cap_s', 10800) // 60} min)")
    out.append(f"- Tool calls: {stats['tool_calls']}")
    fw = stats["first_write_turn"]
    out.append(f"- Turns before the first `write_file`: "
               f"**{fw if fw else 'never wrote a file'}**")
    out.append(f"- Reads of the ZeroZ Stack docs/examples: **{stats['doc_reads']}**")
    out.append(f"- Prompt tokens total {stats['prompt_tokens']}, "
               f"completion {stats['completion_tokens']}")
    out.append(f"- **Largest single prompt: {stats['largest_prompt_tokens']} tokens**")
    reps = stats.get("repeats") or []
    if reps:
        out.append(f"- Looped (same tool call, same arguments, 3+ times): "
                   f"**yes, {len(reps)} distinct calls**")
        for r in reps[:8]:
            out.append(f"  - turn {r['turn']}: `{r['call']}`")
    else:
        out.append("- Looped (same tool call, same arguments, 3+ times): no")
    if stats.get("done_summary"):
        out.append(f"\nThe model's own closing summary:\n\n> "
                   f"{stats['done_summary'][:1500]}\n")

    # ---- what changed --------------------------------------------------
    out.append("\n## What changed in the repository\n")
    _, diffstat = sh("git add -A -N && git diff --stat", repo)
    out.append("```\n" + (diffstat.strip() or "(nothing changed)") + "\n```")
    _, names = sh("git status --porcelain", repo)
    changed = [l[3:] for l in names.splitlines() if l.strip()]

    # ---- build ---------------------------------------------------------
    if not changed:
        out.append(
            "\n> **The model changed nothing.** Everything measured below therefore "
            "describes the repository as it was cloned, not anything the model did. "
            "`master` already compiles and its stub client already uses the stack's UI "
            "components — read those rows as the starting state, not as an achievement.\n")

    out.append("\n## Does it compile?\n")
    build_cmd = ("mvn -o -q -B compile test-compile" if opts.control
                 else "mvn -o -q -B -DfastCompile compile test-compile")
    rc, txt = sh(build_cmd, repo)
    out.append(f"`{build_cmd}` -> exit {rc} — "
               f"**{'compiles' if rc == 0 else 'DOES NOT COMPILE'}**")
    if rc != 0:
        out.append(f"\nFirst error:\n\n```\n{first_error(txt)}\n```")
        out.append(f"\n<details><summary>build output (tail)</summary>\n\n```\n"
                   f"{txt[-4000:]}\n```\n</details>")

    # ---- acceptance test ------------------------------------------------
    out.append("\n## The acceptance test\n")
    if opts.control:
        rel = "src/test/java/com/example/books/BookListTest.java"
        test_cmd = ("mvn -o -q -B test -Dtest=BookListTest "
                    "-Dsurefire.failIfNoSpecifiedTests=false")
    else:
        rel = "bookshelf-demo-server/src/test/java/swarm/accept/BookListTest.java"
        test_cmd = ("mvn -o -q -B test -Dtest=swarm.accept.BookListTest "
                    "-Dsurefire.failIfNoSpecifiedTests=false")
    test_path = os.path.join(repo, rel)
    exists = os.path.isfile(test_path)
    out.append(f"- `{rel}` exists: **{'yes' if exists else 'NO'}**")
    if exists:
        trc, ttxt = sh(test_cmd, repo)
        ran = bool(re.search(r"Tests run:\s*\d+", ttxt))
        out.append(f"- test command exit: **{trc}** — "
                   f"**{'PASSES' if trc == 0 else 'FAILS'}**")
        out.append(f"- surefire reported a test run: {'yes' if ran else 'no'}")
        m = re.search(r"Tests run:.*", ttxt)
        if m:
            out.append(f"- `{m.group(0).strip()}`")
        # surefire is quiet under -q when everything passes, so read the XML report
        rep = os.path.join(repo, "target/surefire-reports")
        if os.path.isdir(rep):
            total = fails = errs = skips = 0
            for f in os.listdir(rep):
                if not f.endswith(".xml"):
                    continue
                x = open(os.path.join(rep, f), encoding="utf-8",
                         errors="replace").read()
                def attr(name):
                    a = re.search(name + r'="(\d+)"', x)
                    return int(a.group(1)) if a else 0
                total += attr("tests"); fails += attr("failures")
                errs += attr("errors"); skips += attr("skipped")
            out.append(f"- surefire report: **{total} tests, {fails} failures, "
                       f"{errs} errors, {skips} skipped**")
        if trc != 0:
            out.append(f"\n<details><summary>test output (tail)</summary>\n\n```\n"
                       f"{ttxt[-4000:]}\n```\n</details>")

    # ---- stack conformance ----------------------------------------------
    if opts.control:
        out.append("\n## What it produced\n")
        for dirpath, dirnames, files in os.walk(os.path.join(repo, "src")):
            dirnames[:] = [d for d in dirnames if d != ".git"]
            for f in sorted(files):
                if f.endswith(".java"):
                    fp = os.path.join(dirpath, f)
                    n = len(open(fp, encoding="utf-8",
                                 errors="replace").read().splitlines())
                    out.append(f"- `{os.path.relpath(fp, repo)}` — {n} lines")
        out.append("\n## Files the model changed\n")
        out.append("```\n" + ("\n".join(changed) if changed else "(none)") + "\n```")
        text = "\n".join(out) + "\n"
        with open(os.path.join(run_dir, "result.md"), "w", encoding="utf-8") as fh:
            fh.write(text)
        print(text)
        return

    out.append("\n## Does it follow the stack the requirements demand?\n")

    server_pom = os.path.join(repo, "bookshelf-demo-server/pom.xml")
    pom_txt = open(server_pom, encoding="utf-8").read() if os.path.isfile(server_pom) else ""
    declared = "zerozstack-store-eclipsestore" in pom_txt
    out.append(f"- Server pom declares `zerozstack-store-eclipsestore` "
               f"(master deliberately does not): **{'yes' if declared else 'NO'}**")

    def grep(pattern, root, exts=(".java",)):
        hits = []
        for dirpath, dirnames, files in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in (".git", "target")]
            for f in files:
                if exts and not f.endswith(exts):
                    continue
                p = os.path.join(dirpath, f)
                try:
                    t = open(p, encoding="utf-8", errors="replace").read()
                except Exception:                                  # noqa: BLE001
                    continue
                if re.search(pattern, t):
                    hits.append(os.path.relpath(p, repo).replace("\\", "/"))
        return hits

    server_src = os.path.join(repo, "bookshelf-demo-server/src")
    client_src = os.path.join(repo, "bookshelf-demo-client/src")

    store_hits = grep(r"EmbeddedStorageManager|zerozstack\.store|com\.zeroz4j\.store",
                      server_src) if os.path.isdir(server_src) else []
    out.append(f"- Persistence goes through EclipseStore: "
               f"**{'yes' if store_hits else 'NO'}**"
               + (f" ({', '.join(store_hits[:5])})" if store_hits else
                  " — nothing in server code references the store"))

    mem_hits = grep(r"new (ArrayList|HashMap|ConcurrentHashMap|CopyOnWriteArrayList)",
                    server_src) if os.path.isdir(server_src) else []
    out.append(f"- Uses a plain in-memory collection in server code: "
               f"{'yes (' + ', '.join(mem_hits[:5]) + ')' if mem_hits else 'no'}")

    ui_hits = grep(r"com\.zeroz4j\.ui", client_src) if os.path.isdir(client_src) else []
    out.append(f"- Client UI uses `com.zeroz4j.ui.*` components: "
               f"**{'yes' if ui_hits else 'NO'}**"
               + (f" ({', '.join(ui_hits[:5])})" if ui_hits else ""))

    out.append("\n### Forbidden technology\n")
    any_forbidden = False
    for label, pattern in FORBIDDEN.items():
        if pattern is None:
            js = []
            for dirpath, dirnames, files in os.walk(repo):
                dirnames[:] = [d for d in dirnames if d not in (".git", "target")]
                for f in files:
                    if f.endswith((".js", ".ts")):
                        js.append(os.path.relpath(os.path.join(dirpath, f), repo))
            js = [j for j in js if j.replace("\\", "/") not in
                  ()]  # index.html only in master; any .js is new
            hit = js
        else:
            hit = grep(pattern, repo, exts=(".java", ".xml"))
        if hit:
            any_forbidden = True
            out.append(f"- **{label}: PRESENT** — {', '.join(str(h) for h in hit[:5])}")
        else:
            out.append(f"- {label}: absent")
    if not any_forbidden:
        out.append("\nNone of the forbidden technologies appears.")

    out.append("\n## Files the model changed\n")
    out.append("```\n" + ("\n".join(changed) if changed else "(none)") + "\n```")

    text = "\n".join(out) + "\n"
    with open(os.path.join(run_dir, "result.md"), "w", encoding="utf-8") as fh:
        fh.write(text)
    print(text)


if __name__ == "__main__":
    main()
