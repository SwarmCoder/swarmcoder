# The unseen-code experiment

One question, and it is the product's core problem: **what has to be in a worker's prompt for it
to write code against a codebase and an API it has never seen?**

## What was already known before this started

From `experiment/plain-loop-ceiling` (branch, worktree `swarmcoder-plain-loop-experiment`), the
same model in a harness with nothing but native tool calling and the repository:

| | ZeroZ Stack task (unfamiliar framework) | Plain Java task (same shape, nothing unfamiliar) |
|---|---|---|
| runs | 3 | 2 |
| turns | 467 | 10 |
| turns to first write | never | 5 |
| files written | **0** | 4 (378 lines) |
| tests | none written | 5, green |
| wall time | 3 h + 3 h + 2 h | 5 min |

So the model can design, write and debug. What it cannot do is acquire an unfamiliar API from
prose documentation and a search tool. The framework's own instructions to agents
(`zeroz4j/docs/AGENT_PROMPTS.md`) say the same thing in a line: *copy the reference example's
directory tree, keep the copied structure, replace the domain code.* Worked code, not documents.

## What this experiment changes, and nothing else

`unseen_loop.py` is `plain_loop.py` with one thing added: the user message may carry a **brief**,
and the cloned repository may be **seeded** with generated files before the model starts. Every
other setting — five tools, native tool calling, thinking ON, temperature 0.2, no nudges, no
kills, no compaction, writes confined to the clone — is byte-identical to the plain-loop harness,
so the brief is the only variable.

The brief is not written by hand. It is produced by a Java tool in this repository,
`com.swarmcoder.knowledge.BriefLab`, from the task's **contracts** (the fully-qualified type names
and members the design fixes) and the codebase's own reference roots. Nothing in it names a
framework: the same code run against a Spring codebase renders a Spring example.

```
mvn -o -q -pl sc-knowledge -am -DskipTests test-compile
mvn -o -q -pl sc-knowledge exec:java -Dexec.classpathScope=test \
    -Dexec.mainClass=com.swarmcoder.knowledge.BriefLab \
    -Dexec.args="--task dev/experiment/unseen-code/bookshelf-task.json \
                 --out dev/experiment/unseen-code/briefs/v1-examples.md \
                 --ingredients examples,deps"
```

Add `--skeleton-dir <tree>` to also generate the task's files into a tree, and
`BRIEFLAB_TRACE=1` to see every candidate project it considered and why one won.

`bookshelf-task.json` holds the task and its contracts. **The contracts were written by hand for
this experiment**, standing in for the PLAN stage, from the story and the two requirement
documents only — the same information the model itself is given.

## The variants

| | brief | tree the model starts on | system prompt |
|---|---|---|---|
| **v1** | the nearest worked example, whole, plus the dependency the build is missing | untouched `master` | the plain one |
| **v2** | v1, plus a note that the task's files already exist and compile, first | `master` + generated skeleton + the pom line, committed | the plain one |
| **v3** | exactly v2 | exactly v2 | SwarmCoder's own rules on when to stop reading, plus its read pause at sixteen |
| **v4** | exactly v1 — the worked example, no skeleton note | untouched `master` | exactly v3 (but the read pause lifted permanently on the first write — a harness defect, see `results.md`) |
| **v5** | exactly v4 | untouched `master` | exactly v3, with the pause re-arming the way the product's does |
| **v6** | exactly v2 | exactly v2 | v3 plus `ask_expert` and `request_skeleton`, and the refusal of jar disassembly |
| **v7** | none at all | untouched `master` | exactly v6 |

v7 is the one that asks whether the help tools can REPLACE the brief: no worked example, no
skeleton, nothing but the requirement documents and the ability to ask.

v4 is v3 with the skeleton taken away, so what the generated files were worth can be separated
from what the worked example was worth.

The plain system prompt's one line about reading is **"Read before guessing an API."** That is the
right thing to say to a worker with nothing but a documentation folder, and it is the wrong thing
to say to a worker whose own message already carries the code. v3 replaces that one sentence with
SwarmCoder's `WORKFLOW_RULES` on the same subject and turns on its read pause; nothing else
changes.

## Running one

The GPU is shared. **One at a time.**

```
python unseen_loop.py --variant v1 --brief briefs/v1-examples.md
python unseen_loop.py --variant v2 --brief briefs/v2-examples-skeleton.md --seed <seed dir>
python unseen_loop.py --variant v3 --brief briefs/v2-examples-skeleton.md \
                      --seed <seed dir> --workflow-rules --read-pause 16
python measure.py --variant v1
```

Caps: 200 turns, 2 hours. Thinking is the server default (ON) and stays on.

The only endpoint any of this may talk to is the free local one,
`http://192.168.0.10:8002/v1`, model `qwen3.8-27b`. The host is asserted at import time, so the
script refuses to start if the constant is ever edited to point somewhere paid.

## Where a run's record lives

`<scratch>/unseen-code/<variant>/` — `transcript.jsonl` (every request and reply),
`transcript.md` (readable), `stats.json` (turns, tool counts, the turn of the first write, token
totals, reasoning totals, wall time, stop reason), `result.md` (the measurement, taken afterwards
by `measure.py`, which runs the builds itself so the verdict does not depend on anything the model
said).

`results.md` in this folder is the comparison across variants.
