#!/usr/bin/env python3
"""
The unseen-code experiment.

The same deliberately minimal harness as the plain-loop ceiling experiment --
one model, native OpenAI tool calling, five tools, no nudges, no kills, no write
set, no compaction -- with ONE thing changed: the user message may carry a BRIEF
built by `com.swarmcoder.knowledge.BriefLab` from the task's contracts and the
codebase's own reference material.

The plain-loop runs established the ceiling: on the ZeroZ Stack task this model
read 43 documents, disassembled the framework jars for 106 shell calls, and over
three runs and 467 turns wrote zero files; on a plain-Java task of the same shape
it was writing at turn 5 and green at turn 10.  This harness exists to find out
what has to be in the brief for the first case to behave like the second.

Everything else is byte-identical to plain_loop.py on purpose, so the only
variable between a plain-loop run and a run here is the brief.

Standard library + `requests` only.

Usage:
    python unseen_loop.py --variant v1 --brief briefs/v1-examples.md
    python unseen_loop.py --variant v2 --brief briefs/v2.md --seed <dir>
"""

import argparse
import json
import os
import shutil
import re
import subprocess
import sys
import time
from urllib.parse import urlparse

import requests

# --------------------------------------------------------------------------
# Hard constraint: the only model endpoint this script may ever talk to is the
# free local one.  Asserted by construction so no edit, argument or env var can
# point it at a paid API by accident.
# --------------------------------------------------------------------------
BASE_URL = "http://192.168.0.10:8002/v1"
MODEL = "qwen3.8-27b"
ALLOWED_HOST = "192.168.0.10"

if urlparse(BASE_URL).hostname != ALLOWED_HOST:
    raise SystemExit("refusing to run: endpoint host is not the free local server")

TEMPERATURE = 0.2
MAX_COMPLETION_TOKENS = 8192
TURN_CAP = 200
WALL_CLOCK_CAP_S = 2 * 60 * 60          # 2 hours

# The control condition: same harness, same model, same endpoint, a task of the
# same size on plain Java with nothing unfamiliar. It separates "cannot build"
# from "cannot learn ZeroZ Stack from its documentation".
CONTROL_TURN_CAP = 120
CONTROL_WALL_CLOCK_CAP_S = 2 * 60 * 60  # 2 hours
# WorkerToolbox.READ_PAUSE_TEXT and BUILD_OR_TEST_COMMAND, verbatim, so a variant that turns the
# forcing rule on is testing SwarmCoder's rule and not a paraphrase of it.
HELP_TOOLS_PAUSE_SUFFIX = (
    "\nIf you are stuck on an API, call ask_expert with the exact question instead of reading "
    "further; if you do not know how to start a file, call request_skeleton with the type name. "
    "Both still work while reading is paused, and so does a build or test command.")

READ_PAUSE_TEXT = (
    "Reading is paused. You have read 16 things and written nothing. Write your best attempt "
    "now with write_file or apply_diff — a wrong file the compiler can correct is worth more "
    "than another page read. Reading resumes after your first write.")
BUILD_OR_TEST_COMMAND = re.compile(r"(?i)\b(mvnw?|gradlew?|javac)\b")

RUN_TIMEOUT_S = 10 * 60                 # per shell command
OUTPUT_TRUNCATE = 8000                  # chars of stdout+stderr returned
STALL_TIMEOUT_S = 20 * 60               # no reply from the model for this long

SWARMCODER = r"C:/work/swarmcoder"
DEMO_SOURCE = SWARMCODER + "/dev/bookshelf-demo"
SCRATCH = os.path.join(os.path.expanduser("~"), ".swarmcoder", "experiment", "unseen-code").replace("\\", "/")

STORY = (
    "Add, edit, and remove books in the personal list. Build the single-user book list "
    "feature end to end: each book stores a title, an author, a publication year, and a "
    "reading status chosen from 'haven't started', 'currently reading', or 'finished', with "
    "an add form, editable fields, a remove action, and a list that shows the latest value "
    "of every field. Acceptance criteria: (1) adding a book with a title, author, publication "
    "year and status makes it appear in the list with those details; (2) editing an existing "
    "book's title, author, publication year or status changes what the list shows; (3) "
    "removing a book deletes it from the list and it no longer appears. Also write a JUnit 5 "
    "acceptance test in `bookshelf-demo-server/src/test/java/swarm/accept/BookListTest.java` "
    "that proves the three criteria through the service, and make it pass."
)

# SwarmDispatcher.buildBundle()'s WORKFLOW_RULES on when to look things up and when to write,
# verbatim except that this harness has no lookup_api and no apply_diff, so the sentence that
# names them names the file tools instead. Everything about what to DO is unchanged.
SWARMCODER_RULES = (
    "When you do not know an API: read the reference material FIRST — the documentation and "
    "examples above, and anything your task message already quotes. Do NOT unpack or decompile "
    "jars (`jar xf`, `javap`, a decompiler) to work an API out — that costs many turns and "
    "tells you less than one page read. Only fall back to inspecting compiled classes when the "
    "documentation has told you there is nothing, and give it a few turns at most before writing "
    "your best attempt and letting the build correct you.\n"
    "A wrong file the compiler can correct is worth far more than another page read.\n")

DOCS_DIR = r"C:/work/zeroz4j/docs"
EXAMPLES_DIR = r"C:/work/zeroz4j/zerozstack-examples"

CONTROL_SOURCE = (r"C:/work/worktrees/swarmcoder-plain-loop-experiment/"
                  r"dev/experiment/plain-loop/control/seed")

CONTROL_TASK = (
    "Build a single-user book list library in plain Java, no frameworks. Each book has a "
    "title, an author, a publication year (int) and a reading status that is exactly one of "
    "'haven't started', 'currently reading', 'finished'. Provide a `BookList` class with "
    "add, edit (any field of an existing book), remove, and list (returning the latest value "
    "of every field), and make the list persist between program runs by saving to and loading "
    "from a file in a directory given to the constructor (plain Java I/O, any format you "
    "choose). Reject an invalid status with IllegalArgumentException. Write a JUnit 5 test "
    "class `BookListTest` in `src/test/java/com/example/books` that proves: (1) adding a book "
    "with all four fields makes it appear in the list with those values; (2) editing an "
    "existing book's title, author, year or status changes what the list returns; (3) removing "
    "a book deletes it and it no longer appears; (4) books survive a new BookList instance on "
    "the same directory. Make `mvn -o -q -B test` pass."
)


def control_system_prompt(repo: str) -> str:
    """The same prompt as the ZeroZ runs with the two stack sentences removed."""
    return (
        "You are a software engineer.\n\n"
        f"The repository is at {repo}\n\n"
        "Build it with:  mvn -o -q -B -DfastCompile compile test-compile\n"
        "Run the tests with:  mvn -o -q -B test\n\n"
        f"The ZeroZ Stack documentation is at {DOCS_DIR} and its examples are at "
        f"{EXAMPLES_DIR}. You can read both with the same tools.\n\n"
        + reading +
        "\nCall done when the build is green and the task is complete."
    )


def system_prompt(repo: str, workflow_rules: bool) -> str:
    """
    The plain harness's own prompt, unchanged, plus one optional block.

    <p>The plain prompt ends with **"Read before guessing an API."** That was the right thing to
    say to a worker with nothing but a documentation folder. It is the wrong thing to say to a
    worker whose message already carries the code, and variants 1 and 2 both did exactly what it
    told them: 92 and 90+ turns of reading, no file written, the build never run. The optional
    block replaces that one sentence with SwarmCoder's own WORKFLOW_RULES on the same subject,
    verbatim from SwarmDispatcher.buildBundle -- so the variant tests the product's real
    instruction and not a paraphrase written to win.
    """
    reading = ("Read before guessing an API.\n" if not workflow_rules
               else SWARMCODER_RULES)
    return (
        "You are a software engineer.\n\n"
        f"The repository is at {repo}\n\n"
        "Build it with:  mvn -o -q -B -DfastCompile compile test-compile\n"
        "Run the tests with:  mvn -o -q -B test\n\n"
        f"The ZeroZ Stack documentation is at {DOCS_DIR} and its examples are at "
        f"{EXAMPLES_DIR}. You can read both with the same tools.\n\n"
        + reading +
        "\nCall done when the build is green and the task is complete."
    )


# --------------------------------------------------------------------------
# Tools
# --------------------------------------------------------------------------
TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "list_files",
            "description": "List the files and directories in a directory.",
            "parameters": {
                "type": "object",
                "properties": {
                    "dir": {"type": "string", "description": "Directory path."}
                },
                "required": ["dir"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Read the contents of a file.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "File path."}
                },
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "write_file",
            "description": "Write a file, creating or overwriting it.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "File path."},
                    "content": {"type": "string", "description": "Full file contents."},
                },
                "required": ["path", "content"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "run",
            "description": "Run a shell command in the repository directory.",
            "parameters": {
                "type": "object",
                "properties": {
                    "command": {"type": "string", "description": "Shell command."}
                },
                "required": ["command"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "ask_expert",
            "description": (
                "ASK FOR HELP when an API or a framework behaviour is beyond you — this is "
                "what it is for and using it is not a failure. Give the exact question and what "
                "you already tried. You get back real code from this project that makes that "
                "call, its imports, and the build line if one is missing. Use it INSTEAD of "
                "unpacking or disassembling a jar, which is refused."),
            "parameters": {
                "type": "object",
                "properties": {
                    "question": {"type": "string", "description": "The exact API question."},
                    "what_i_tried": {"type": "string", "description": "What you already tried."},
                },
                "required": ["question"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "request_skeleton",
            "description": (
                "ASK FOR A STARTING POINT when you do not know how to begin a file. Give the type "
                "name your task must deliver, or describe it. You get back a version that "
                "compiles, with this project's own annotations, imports and injected fields "
                "already on it and every method body a TODO for you to fill in."),
            "parameters": {
                "type": "object",
                "properties": {
                    "type_or_task": {"type": "string",
                                     "description": "A type name, or a description of it."},
                },
                "required": ["type_or_task"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "done",
            "description": "Call this when the build is green and the task is complete.",
            "parameters": {
                "type": "object",
                "properties": {
                    "summary": {"type": "string", "description": "What you did."}
                },
                "required": ["summary"],
            },
        },
    },
]


def resolve(repo: str, path: str) -> str:
    """Absolute paths pass through; relative ones are relative to the repo."""
    if os.path.isabs(path) or (len(path) > 1 and path[1] == ":"):
        return os.path.normpath(path)
    return os.path.normpath(os.path.join(repo, path))


def tool_list_files(repo, args):
    d = resolve(repo, args["dir"])
    if not os.path.isdir(d):
        return f"ERROR: not a directory: {d}"
    entries = []
    for name in sorted(os.listdir(d)):
        full = os.path.join(d, name)
        entries.append(name + ("/" if os.path.isdir(full) else ""))
    return "\n".join(entries) if entries else "(empty)"


def tool_read_file(repo, args):
    p = resolve(repo, args["path"])
    if not os.path.isfile(p):
        return f"ERROR: no such file: {p}"
    try:
        with open(p, "r", encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except Exception as exc:                                   # noqa: BLE001
        return f"ERROR: {exc}"


def tool_write_file(repo, args):
    p = resolve(repo, args["path"])
    # The one boundary that is NOT part of the experiment: writes stay inside the
    # scratch clone.  This is machine safety, not agent scaffolding -- nothing
    # about the task requires writing outside the repository.
    if not os.path.normcase(p).startswith(os.path.normcase(os.path.normpath(repo))):
        return f"ERROR: writes are only allowed inside {repo}"
    os.makedirs(os.path.dirname(p), exist_ok=True)
    try:
        with open(p, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(args["content"])
        return f"Wrote {p} ({len(args['content'])} bytes)"
    except Exception as exc:                                   # noqa: BLE001
        return f"ERROR: {exc}"


def tool_run(repo, args):
    cmd = args["command"]
    try:
        proc = subprocess.Popen(
            cmd, shell=True, cwd=repo,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace",
        )
    except Exception as exc:                                   # noqa: BLE001
        return f"ERROR: could not start command: {exc}"
    try:
        out, _ = proc.communicate(timeout=RUN_TIMEOUT_S)
        code = proc.returncode
    except subprocess.TimeoutExpired:
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)],
                       capture_output=True)
        try:
            out, _ = proc.communicate(timeout=30)
        except Exception:                                      # noqa: BLE001
            out = ""
        out = (out or "") + f"\n[command killed after {RUN_TIMEOUT_S}s]"
        code = -1
    out = out or ""
    if len(out) > OUTPUT_TRUNCATE:
        half = OUTPUT_TRUNCATE // 2
        out = (out[:half] + f"\n... [{len(out) - OUTPUT_TRUNCATE} chars truncated] ...\n"
               + out[-half:])
    return f"exit code: {code}\n{out}"


# --------------------------------------------------------------------------
# The worker's 911, answered by the REAL desk
# --------------------------------------------------------------------------
# One JVM per call, deliberately. A Python re-implementation would measure a second piece of code
# that nothing in production runs; this measures com.swarmcoder.knowledge.ExpertDesk itself, the
# same class the WorkerToolbox calls. The stand-in for production's paid utility model is the same
# free local endpoint the worker runs on, given the reference material as context, which makes the
# escalation here weaker than production's and any result a lower bound.
HELPDESK_CP = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".helpdesk-cp.txt")
HELPDESK_TASK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bookshelf-task.json")

HELP_SOURCES = []


def _help_desk(kind, *args):
    if not os.path.isfile(HELPDESK_CP):
        return "the help desk is not built; run mvn -o -q -pl sc-knowledge -am test-compile"
    with open(HELPDESK_CP, encoding="utf-8") as fh:
        classpath = fh.read().strip()
    try:
        proc = subprocess.run(
            ["java", "-cp", classpath, "com.swarmcoder.knowledge.HelpDeskCli", kind,
             HELPDESK_TASK] + [a for a in args if a],
            cwd=os.path.dirname(os.path.abspath(__file__)) + "/../../..",
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=900)
    except Exception as exc:                                       # noqa: BLE001
        HELP_SOURCES.append("ERROR")
        return f"the help desk could not be reached: {exc}"
    lines = (proc.stdout or "").splitlines()
    if not lines:
        HELP_SOURCES.append("ERROR")
        return "the help desk returned nothing:\n" + (proc.stderr or "")[-2000:]
    HELP_SOURCES.append(lines[0].strip())
    return "\n".join(lines[1:])


def tool_ask_expert(repo, args):
    return _help_desk("ask", args.get("question", ""), args.get("what_i_tried", ""))


def tool_request_skeleton(repo, args):
    return _help_desk("skeleton", args.get("type_or_task", ""))


DISPATCH = {
    "list_files": tool_list_files,
    "read_file": tool_read_file,
    "write_file": tool_write_file,
    "run": tool_run,
    "ask_expert": tool_ask_expert,
    "request_skeleton": tool_request_skeleton,
}

# The refusal, transplanted from WorkerToolbox.REVERSE_ENGINEERING / REVERSE_ENGINEERING_REFUSED.
REVERSE_ENGINEERING = re.compile(
    r"(?i)(\bjavap\b|\bjar\s+[a-z]*[tx][a-z]*f\b|\bunzip\b[^|;&]*\.jar"
    r"|\bfind\b[^|;&]*-name[^|;&]*\.jar|\bcfr\b|\bprocyon\b|\bjd-cli\b)")
REVERSE_ENGINEERING_REFUSED = (
    "Refused: do not reverse-engineer the framework. Call ask_expert with the exact API "
    "question instead — it answers from code in this project's reference material that "
    "compiles today, which is more reliable than a disassembly and costs one turn instead of "
    "twenty.")


# --------------------------------------------------------------------------
# Loop
# --------------------------------------------------------------------------
def trunc(s, n=400):
    s = s if isinstance(s, str) else json.dumps(s)
    return s if len(s) <= n else s[:n] + f" ... [{len(s) - n} more chars]"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--variant", required=True,
                    help="names the run directory, e.g. v1 or v2-repeat")
    ap.add_argument("--brief", default=None,
                    help="a markdown file appended to the user message. This IS the "
                         "experiment: everything else is held identical.")
    ap.add_argument("--help-tools", action="store_true",
                    help="offer ask_expert and request_skeleton, and REFUSE the commands that "
                         "reverse-engineer the framework. The two go together: 'do not take the "
                         "jar apart' is only fair when there is somebody to ask instead. Both "
                         "tools are answered by the real com.swarmcoder.knowledge.ExpertDesk "
                         "through HelpDeskCli, not by a re-implementation.")
    ap.add_argument("--workflow-rules", action="store_true",
                    help="replace the plain prompt's 'Read before guessing an API.' with "
                         "SwarmCoder's own rules on the same subject.")
    ap.add_argument("--read-pause", type=int, default=0,
                    help="SwarmCoder's own forcing rule, transplanted: after this many "
                         "looking-only tool calls with nothing written, read_file, list_files "
                         "and any non-build shell command stop returning real answers and say "
                         "so instead. 0 disables it, which is the plain harness. The text and "
                         "the threshold are WorkerToolbox.READ_PAUSE_TEXT and "
                         "EarlyKillEnforcer.INVESTIGATION_TOOL_CALLS_BEFORE_SECOND_NUDGE, "
                         "verbatim.")
    ap.add_argument("--seed", default=None,
                    help="a directory to copy over the cloned repository before the "
                         "model starts -- how a generated skeleton reaches its tree.")
    ap.add_argument("--no-thinking", action="store_true",
                    help="send chat_template_kwargs {enable_thinking: false}. "
                         "The server's default is thinking ON; SwarmCoder workers "
                         "run with it OFF, so this is a controlled variable.")
    ap.add_argument("--control", action="store_true",
                    help="the control condition: a task of the same size on plain "
                         "Java, nothing unfamiliar, no documentation folder.")
    opts = ap.parse_args()
    thinking = not opts.no_thinking
    control = opts.control

    turn_cap = CONTROL_TURN_CAP if control else TURN_CAP
    wall_cap = CONTROL_WALL_CLOCK_CAP_S if control else WALL_CLOCK_CAP_S
    source = CONTROL_SOURCE if control else DEMO_SOURCE

    run_dir = os.path.join(SCRATCH, opts.variant)
    repo = os.path.join(run_dir, "repo")
    if os.path.exists(run_dir):
        shutil.rmtree(run_dir, ignore_errors=True)
    os.makedirs(run_dir, exist_ok=True)

    print(f"cloning {source} -> {repo}")
    if os.path.isdir(os.path.join(source, ".git")):
        subprocess.run(["git", "clone", "-q", "--no-hardlinks", "--branch",
                        "master", "--single-branch", source, repo], check=True)
    else:
        # The control seed is committed as plain files, not as a nested git
        # repository. Copy it and make the first commit here, so the model
        # still starts on a clean repository with exactly one commit.
        shutil.copytree(source, repo)
        subprocess.run(["git", "init", "-q", "-b", "master"], cwd=repo, check=True)
        subprocess.run(["git", "add", "-A"], cwd=repo, check=True)
        subprocess.run(["git", "-c", "user.name=plain-loop",
                        "-c", "user.email=plain-loop@localhost",
                        "commit", "-q", "-m", "seed"], cwd=repo, check=True)
    if opts.seed:
        # A generated skeleton reaches the model the way SwarmCoder would deliver it: already
        # committed on the branch the worker starts from, so its first write is an edit.
        shutil.copytree(opts.seed, repo, dirs_exist_ok=True)
        subprocess.run(["git", "add", "-A"], cwd=repo, check=True)
        subprocess.run(["git", "-c", "user.name=unseen-loop",
                        "-c", "user.email=unseen-loop@localhost",
                        "commit", "-q", "-m", "skeleton"], cwd=repo, check=True)
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo,
                          capture_output=True, text=True).stdout.strip()
    print(f"HEAD {head}")

    # The help tools exist for this run or they do not. Filtering the list rather than always
    # offering them keeps every earlier variant's tool set byte-identical to what it was.
    helping = opts.help_tools
    tools = [t for t in TOOLS
             if helping or t["function"]["name"] not in ("ask_expert", "request_skeleton")]

    brief = ""
    brief_chars = 0
    if opts.brief:
        # THE experiment. Everything else in this file is held byte-identical to the plain-loop
        # harness so that this file's contents are the only variable between the two.
        with open(opts.brief, encoding="utf-8") as fh:
            brief = fh.read()
        brief_chars = len(brief)

    if control:
        user_msg = CONTROL_TASK
    else:
        with open(os.path.join(SWARMCODER, "dev", "bookshelf-tech-requirements.md"),
                  encoding="utf-8") as fh:
            tech_req = fh.read()
        with open(os.path.join(SWARMCODER, "dev", "bookshelf-requirements.md"),
                  encoding="utf-8") as fh:
            func_req = fh.read()
        user_msg = (
        f"{STORY}\n\n"
        f"--- bookshelf-requirements.md ---\n\n{func_req}\n\n"
        f"--- bookshelf-tech-requirements.md ---\n\n{tech_req}\n"
    )
    user_msg = user_msg + chr(10) + brief if brief else user_msg

    messages = [
        {"role": "system",
         "content": control_system_prompt(repo) if control
                    else system_prompt(repo, opts.workflow_rules)},
        {"role": "user", "content": user_msg},
    ]

    jsonl = open(os.path.join(run_dir, "transcript.jsonl"), "w", encoding="utf-8")
    md = open(os.path.join(run_dir, "transcript.md"), "w", encoding="utf-8")
    md.write(f"# Unseen-code run {opts.variant}\n\n")
    md.write(f"- brief `{opts.brief}` ({brief_chars} chars)\n")
    md.write(f"- seed `{opts.seed}`\n")
    md.write(f"- help tools `{opts.help_tools}`, workflow rules `{opts.workflow_rules}`, "
             f"read pause `{opts.read_pause}`\n")
    md.write(f"- repo `{repo}` at `{head}`\n- model `{MODEL}` @ `{BASE_URL}`\n")
    md.write(f"- temperature {TEMPERATURE}, turn cap {turn_cap}, "
             f"wall cap {wall_cap // 3600}h\n")
    if control:
        md.write("- **CONTROL RUN** - plain Java, no framework, no docs folder\n")
    thinking_label = ("ON (server default)" if thinking
                      else "OFF (chat_template_kwargs enable_thinking=false)")
    md.write(f"- **thinking {thinking_label}**\n\n")

    stats = {
        "run": opts.variant,
        "brief": opts.brief,
        "brief_chars": brief_chars,
        "seed": opts.seed,
        "control": control, "head": head, "model": MODEL,
        "thinking": "on" if thinking else "off",
        "turn_cap": turn_cap, "wall_cap_s": wall_cap,
        "turns": 0, "tool_calls": {}, "first_write_turn": None,
        "doc_reads": 0, "prompt_tokens": 0, "completion_tokens": 0,
        "largest_prompt_tokens": 0, "stop_reason": None,
        "done_summary": None, "repeats": [],
        "reasoning_turns": 0, "reasoning_chars": 0, "reasoning_tokens": 0,
        "read_pause_after": opts.read_pause, "read_pauses": 0,
        "help_tools": opts.help_tools, "help_calls": 0, "help_sources": {},
        "reverse_engineering_refused": 0,
        "workflow_rules": opts.workflow_rules,
    }
    seen_calls = {}
    investigation = [0]
    logged_upto = 0
    started = time.time()

    while True:
        if stats["turns"] >= turn_cap:
            stats["stop_reason"] = f"turn cap {turn_cap} reached"
            break
        if time.time() - started > wall_cap:
            stats["stop_reason"] = "wall clock cap reached"
            break

        payload = {
            "model": MODEL,
            "messages": messages,
            "tools": tools,
            "tool_choice": "auto",
            "temperature": TEMPERATURE,
            "max_tokens": MAX_COMPLETION_TOKENS,
        }
        if not thinking:
            payload["chat_template_kwargs"] = {"enable_thinking": False}
        # Log only the messages appended since the last request. Logging the whole
        # payload each turn is O(n^2) and produced a 63 MB file on the first run;
        # the conversation is fully reconstructible from these deltas.
        if logged_upto == 0:
            jsonl.write(json.dumps({"kind": "request", "turn": stats["turns"] + 1,
                                    "settings": {k: v for k, v in payload.items()
                                                 if k != "messages"},
                                    "messages": messages}) + "\n")
        else:
            jsonl.write(json.dumps({"kind": "request", "turn": stats["turns"] + 1,
                                    "new_messages": messages[logged_upto:]}) + "\n")
        logged_upto = len(messages)
        jsonl.flush()

        try:
            resp = requests.post(f"{BASE_URL}/chat/completions", json=payload,
                                 timeout=STALL_TIMEOUT_S)
        except requests.exceptions.Timeout:
            stats["stop_reason"] = f"stalled: no reply within {STALL_TIMEOUT_S}s"
            break
        except Exception as exc:                               # noqa: BLE001
            stats["stop_reason"] = f"request failed: {exc}"
            break

        if resp.status_code != 200:
            stats["stop_reason"] = f"HTTP {resp.status_code}: {trunc(resp.text, 1500)}"
            jsonl.write(json.dumps({"kind": "error", "turn": stats["turns"] + 1,
                                    "status": resp.status_code,
                                    "body": resp.text[:20000]}) + "\n")
            break

        body = resp.json()
        jsonl.write(json.dumps({"kind": "response", "turn": stats["turns"] + 1,
                                "body": body}) + "\n")
        jsonl.flush()

        stats["turns"] += 1
        turn = stats["turns"]
        usage = body.get("usage") or {}
        pt = usage.get("prompt_tokens", 0)
        stats["prompt_tokens"] += pt
        stats["completion_tokens"] += usage.get("completion_tokens", 0)
        stats["largest_prompt_tokens"] = max(stats["largest_prompt_tokens"], pt)

        choice = body["choices"][0]
        msg = choice["message"]
        content = msg.get("content") or ""
        calls = msg.get("tool_calls") or []

        # Thinking mode is a controlled variable: record whether the server
        # actually returned reasoning on this turn, and how much.
        reasoning = msg.get("reasoning_content") or ""
        rtok = usage.get("reasoning_tokens", 0) or 0
        if reasoning:
            stats["reasoning_turns"] += 1
            stats["reasoning_chars"] += len(reasoning)
        stats["reasoning_tokens"] += rtok

        md.write(f"\n## Turn {turn}  (prompt {pt} tok, "
                 f"completion {usage.get('completion_tokens', 0)} tok, "
                 f"reasoning {len(reasoning)} chars / {rtok} tok, "
                 f"finish {choice.get('finish_reason')})\n\n")
        if content.strip():
            md.write("**assistant:** " + trunc(content.strip(), 1200) + "\n\n")

        assistant_msg = {"role": "assistant", "content": content}
        if calls:
            assistant_msg["tool_calls"] = calls
        messages.append(assistant_msg)

        if not calls:
            md.write("_no tool call -- prompting to continue_\n\n")
            messages.append({"role": "user",
                             "content": "Continue. Use a tool, or call done."})
            continue

        finished = False
        for call in calls:
            fn = call["function"]["name"]
            raw = call["function"].get("arguments") or "{}"
            stats["tool_calls"][fn] = stats["tool_calls"].get(fn, 0) + 1
            try:
                args = json.loads(raw) if isinstance(raw, str) else raw
            except Exception as exc:                           # noqa: BLE001
                result = f"ERROR: could not parse arguments: {exc}"
                md.write(f"- `{fn}` BAD ARGS `{trunc(raw, 300)}`\n")
                messages.append({"role": "tool", "tool_call_id": call["id"],
                                 "content": result})
                continue

            key = fn + "|" + json.dumps(args, sort_keys=True)[:300]
            seen_calls[key] = seen_calls.get(key, 0) + 1
            if seen_calls[key] == 3:
                stats["repeats"].append({"turn": turn, "call": trunc(key, 200)})

            if fn == "done":
                stats["done_summary"] = args.get("summary", "")
                stats["stop_reason"] = "done"
                md.write(f"- `done`: {trunc(stats['done_summary'], 800)}\n")
                finished = True
                break

            if fn == "write_file":
                if stats["first_write_turn"] is None:
                    stats["first_write_turn"] = turn
                # WorkerToolbox.recordWrite(): the counter goes back to zero and the pause RE-ARMS
                # after another sixteen looking-only calls. Measured on the first version of this
                # harness, which lifted the pause permanently instead: the worker satisfied it with
                # a one-line edit to a pom and went straight back to reading, for another thirty
                # turns. A rule that can be paid off once is not a rule.
                investigation[0] = 0

            # Transplanted from WorkerToolbox.exec: taking the framework apart is refused, and
            # the refusal names what to do instead. Counted like any other look, so it cannot be
            # used to buy turns.
            if helping and fn == "run" and REVERSE_ENGINEERING.search(args.get("command", "")):
                stats["reverse_engineering_refused"] = \
                    stats.get("reverse_engineering_refused", 0) + 1
                investigation[0] += 1
                md.write("- `run` REFUSED (reverse-engineering)\n")
                messages.append({"role": "tool", "tool_call_id": call["id"],
                                 "content": REVERSE_ENGINEERING_REFUSED})
                continue

            # The forcing rule, when this variant is running with it. Counted and applied exactly
            # as WorkerToolbox does: a build or test command is always let through, because a
            # worker that has stopped writing still needs the compiler's verdict on what it wrote.
            if opts.read_pause and fn not in ("ask_expert", "request_skeleton"):
                looking = fn in ("read_file", "list_files") or (
                    fn == "run" and not BUILD_OR_TEST_COMMAND.search(args.get("command", "")))
                if looking:
                    investigation[0] += 1
                    if investigation[0] > opts.read_pause:
                        stats["read_pauses"] = stats.get("read_pauses", 0) + 1
                        md.write(f"- `{fn}` PAUSED (investigation call "
                                 f"{investigation[0]})\n")
                        messages.append({"role": "tool", "tool_call_id": call["id"],
                                         "content": READ_PAUSE_TEXT
                                         + (HELP_TOOLS_PAUSE_SUFFIX if helping else "")})
                        continue

            handler = DISPATCH.get(fn)
            if handler is None:
                result = f"ERROR: no such tool: {fn}"
            else:
                if fn in ("read_file", "list_files"):
                    target = str(args.get("path") or args.get("dir") or "")
                    tn = os.path.normcase(target).replace("\\", "/")
                    if "zeroz4j/docs" in tn or "zerozstack-examples" in tn:
                        stats["doc_reads"] += 1
                t0 = time.time()
                try:
                    result = handler(repo, args)
                except KeyError as exc:
                    result = f"ERROR: missing argument {exc}"
                except Exception as exc:                       # noqa: BLE001
                    result = f"ERROR: {exc}"
                dt = time.time() - t0
                if fn == "run":
                    md.write(f"- `run` ({dt:.0f}s) `{trunc(args.get('command', ''), 200)}`"
                             f"\n  -> {trunc(result, 700)}\n")
                elif fn == "write_file":
                    md.write(f"- `write_file` `{args.get('path')}` "
                             f"({len(args.get('content', ''))} bytes)\n")
                else:
                    md.write(f"- `{fn}` `{trunc(args.get('path') or args.get('dir'), 200)}`"
                             f" -> {trunc(result, 300)}\n")

            messages.append({"role": "tool", "tool_call_id": call["id"],
                             "content": result})

        md.flush()
        # Checkpoint every turn: a run that is killed from outside still leaves
        # a usable record instead of nothing.
        stats["wall_seconds"] = round(time.time() - started, 1)
        stats["repo"] = repo
        stats["help_calls"] = len(HELP_SOURCES)
        sources = {}
        for source in HELP_SOURCES:
            sources[source] = sources.get(source, 0) + 1
        stats["help_sources"] = sources
        with open(os.path.join(run_dir, "stats.json"), "w", encoding="utf-8") as fh:
            json.dump(stats, fh, indent=2)
        print(f"turn {turn}: {[c['function']['name'] for c in calls]} "
              f"prompt={pt}", flush=True)
        if finished:
            break

    stats["wall_seconds"] = round(time.time() - started, 1)
    stats["repo"] = repo
    md.write(f"\n---\n\nStopped: {stats['stop_reason']} after "
             f"{stats['turns']} turns, {stats['wall_seconds']}s\n")
    md.close()
    jsonl.close()
    with open(os.path.join(run_dir, "stats.json"), "w", encoding="utf-8") as fh:
        json.dump(stats, fh, indent=2)
    print(json.dumps(stats, indent=2))


if __name__ == "__main__":
    main()
