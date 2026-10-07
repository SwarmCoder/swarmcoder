# What makes the model start

Every row below is measured, in the same harness, on the same task, against the same free local
model with thinking ON. The only thing that changes between rows is what the user message carries
and what is already on the tree.

## The two rows that were already known

From `experiment/plain-loop-ceiling`. Same harness, no brief.

| | ZeroZ Stack task | Plain Java task (control) |
|---|---|---|
| runs | 3 | 2 |
| turns | 467 total (163 + 168 + 136) | 10 + 120 |
| turns to first write | **never** | **5** |
| files written | **0** | 4 (378 lines) |
| compiles | starting state, unchanged | yes |
| acceptance test | never written | 5 tests, green |
| largest prompt | 146,268 tokens | 6,585 tokens |
| reasoning tokens | 302,056 (run 1) | 5,992 |
| wall time | 3 h (cap) | 5 min |
| what it did instead | read 43 documents, then `javap` on the framework jars, 106 shell calls | wrote the code |

## The rows this experiment adds

Every run's full record was kept in `runs/<variant>/`. Those transcripts are not part of the public repository.

| variant | brief | tree | turns | to first write | files | build ever run | largest prompt | reasoning tokens | wall |
|---|---|---|---|---|---|---|---|---|---|
| **v1** | the nearest worked example, whole (48,035 chars) | untouched | 92 | **never** | **0** | **never** | 126,971 tok | 42,871 | 34 min |
| **v2** | v1 + "these files exist and compile", first | + skeleton, committed | 93 | **never** | **0** | **never** | 86,188 tok | 16,368 | 15 min |
| **v3** | v2, unchanged | v2, unchanged | **34, `done`** | **7** | **13** | yes, repeatedly | 62,604 tok | 6,521 | **13 min** |
| **v4** | v1, no skeleton | untouched | 44 | 11 | 1 (a pom line) | yes | 46,020 tok | — | 16 min |
| **v5** | v1, no skeleton | untouched | **49, `done`** | **11** | **13** | yes, repeatedly | 51,932 tok | 22,949 | 22 min |
| **v6** | v2 + help tools | v2 | **38, `done`** | **11** | **14** | yes, repeatedly | see runs/v6 | — | 27 min |
| **v7** | **none at all** + help tools | untouched | **90, `done`** | **9** | **22** | yes, repeatedly | see runs/v7 | — | 41 min |

v3 changes one sentence of the system prompt and nothing else: the plain harness's "Read before
guessing an API." becomes SwarmCoder's own rules on the same subject, and SwarmCoder's read
pause is turned on at sixteen looking-only calls.

## v1 — worked examples alone

**It did not start.** 92 turns, 108 tool calls, 66 shell commands, 33 file reads, no file written,
and the build never invoked once — not `mvn compile`, not anything. Stopped by hand at turn 92
rather than at the two-hour cap, because SwarmCoder's own no-progress rule kills a worker at 24
looking-only calls with nothing written and this had made 108.

What changed from the plain-loop runs is **what it read, not whether it wrote**. Without the brief
it read documentation to find out what the API is. With the brief it read the framework's own
source and bytecode to find out whether the API it had been handed was *true*:

- extracted the `zerozdb` sources jar and read `ZeroZDbNode`, `DbCommand`, `DbQuery`,
  `WriteContext`
- `jar tf` over `zerozstack-shared-api` to see what is in it
- `javap -c -p` over the annotation processor, to work out how it generates a serializer
- `javap -v -p` over the CDI producer, to confirm the database node can be injected at all

Every one of those answers agreed with the example it had already been given. The 38 disassembly
and jar commands were confirmation, not discovery.

Two things make this worth stating precisely.

**It was not wrong to be suspicious.** The reference folder is at 0.9.0 and the project builds
against 0.8.0-SNAPSHOT. Documentation and examples of one version, artefacts of another, is
exactly the case that produces confidently wrong code, and the brief said nothing either way.

**It never used the compiler.** The cheapest possible way to find out whether the API is right is
to write the file and build it — three minutes, and the answer is authoritative. It never did.
Given a page and a compiler it chose the page, ninety-two times.

## v2 — the files already there, and compiling

**It did not start either.** The skeleton was found immediately: at turn 3 the model listed the
tree, saw `Book.java`, `BookService.java`, `BookServiceImpl.java`, `ReadingStatus.java`,
`BookshelfView.java` and `swarm/accept/BookListTest.java`, and read them. It had been told, in the
first paragraph of its message, that those files compile against the exact versions this project
builds against and that there was nothing left to verify.

It then spent the rest of the run verifying. Nineteen `javap` and `jar` commands over
`zerozstack-store-eclipsestore`, `zerozstack-shared-api`, the annotation processor and the
generated `Product_Rules`, working out `TenantStorageProvider`, `StoreMode`, `DataRootProvider`,
`TenantResolver`, the validation annotations' default values and the UI component set. Not one
`mvn compile`. Not one file written.

So the second variant changed the same thing the first one did — what it reads — and not the
thing that matters.

## What both variants have in common, and what it points at

The plain harness's system prompt ends with one line about reading:

> **Read before guessing an API.**

That was the right instruction when the worker had nothing but a documentation folder, and it is
still there, unchanged, in v1 and v2 — where the worker's own message already carries the whole
working implementation and, in v2, the files themselves. Both variants did exactly what it says.

SwarmCoder's own workers are told the opposite, in so many words:

> Do NOT unpack or decompile jars (`jar xf`, `javap`, a decompiler) to work an API out — that
> costs many turns and tells you less than one lookup_api call. … give it a few turns at most
> before writing your best attempt and letting the build correct you.
>
> After sixteen reads without a write, reading pauses until you write … a wrong file the compiler
> can correct is worth far more than another page read.

Measuring the product's brief under the opposite instruction does not measure the product. The
next variant changes that one sentence and nothing else.

## v3 — the same brief, under the product's own rules about when to stop reading

One sentence of the system prompt changed, and SwarmCoder's read pause was turned on. The brief,
the tree, the tools, the model, the temperature and the thinking mode are all exactly v2's.

**It wrote at turn 7.** The pause fired on the seventeenth looking-only call, at turn 6. The very
next turn opened with

> I have the full picture from the task message and the existing files. Let me write all the
> implementation files now.

and wrote `ReadingStatus`, `Book` and `BookService`. Turn 8 wrote the whole server side —
`DataRoot`, `BookCommands`, `BookQueries`, `BookServiceImpl`, `DefaultDataRootProvider`,
`DefaultTenantResolver` — which is, file for file, the shape of the worked example it had been
given, with the domain names replaced. That is the brief doing exactly the job it was built for,
in the first eight turns of the run.

**It called `done` at turn 34, thirteen minutes in.** Independently measured afterwards, from the
repository it left behind:

- `mvn -o -q -B -DfastCompile compile test-compile` — **exit 0**
- `swarm/accept/BookListTest` — **3 tests, 0 failures**, run through the service against a real
  embedded store opened in a temp directory, not a mock
- the server build now declares `com.zeroz4j:zerozstack-store-eclipsestore`, which `master`
  deliberately does not
- persistence is the stack's own idiom: a `DataRoot` object graph, writes as `DbCommand`s that
  call `ctx.edit(...)` on every level they change, reads as a `DbQuery` that copies out of the
  graph rather than returning a live node
- the client is `com.zeroz4j.ui.*` components; the TeaVM browser bundle compiles (339 KB of
  `classes.js`)
- no Spring, no JPA, no REST, no JSON, no JavaScript source, no Vaadin

One line of the automatic measurement is wrong and worth naming: it reports "Persistence goes
through EclipseStore: NO". It looks for `storage.store(...)` and a direct EclipseStore import,
which is the framework's 0.7-era idiom; the code uses the current one, which reaches the same
store through `ZeroZDbNode`. The store dependency, the commands, the queries and the reopen in
the example's own test are all there.

Compare: 92 turns and 93 turns of the same model, on the same task, with the same information in
front of it, writing nothing. And 1 documentation read in v3 against 24 in v1.

## What the four runs say, together

**There are two levers and they do different jobs.**

The first is **what the worker is told**. The plain-loop runs and the control settled that: the
model can design, write and debug, and what it cannot do is turn prose about an unfamiliar
framework into an API it will commit to. The worked example fixes that, and v3 shows it fixing it
— when the model finally wrote, at turn 7, it wrote the example's structure with its own domain
names in it, command classes, store root, provider, tenant resolver and all, including the
`ctx.edit(...)` discipline the technical requirements call the single most likely way to get this
project wrong.

The second is **whether anything makes it stop reading**. v1 and v2 settled that: with the same
information in front of it and nothing telling it to stop, this model read for 92 and 93 turns and
never wrote a line, never ran the build, and spent 38 and 19 shell commands respectively
disassembling artefacts to confirm what it had already been handed. Better information changed
what it read. It did not change whether it wrote.

Neither lever substitutes for the other, and it is worth being precise about why:

- **A forcing rule with a bad brief** produces a worker that writes early and writes the wrong
  API. That is the failure the read pause was built for and it is why the brief matters.
- **A good brief with no forcing rule** produces a worker that never writes at all. That is v1 and
  v2, measured twice.

And the size of it: v3 finished the whole feature — shared model, service interface, server
persistence, browser screen and three passing acceptance tests — in **34 turns and 13 minutes**,
against 467 turns and eight hours of the same model on the same task with no brief and no rule.

SwarmCoder already has the second lever — nudges at eight and sixteen looking-only calls, a read
pause at sixteen, a no-progress kill at twenty-four, and a workflow rule that says in advance that
all of this will happen. This work adds the first.

## The caveats worth stating

- **The contracts were written by hand.** They stand in for the PLAN stage and were derived from
  the story and the two requirement documents, which the model is also given. A real run's
  contracts come from the architect, and a task whose design states none gets no worked example
  at all and falls back to what the brief did before.
- **v1 and v2 were stopped by hand**, at turns 92 and 93, not at the two-hour cap. SwarmCoder's
  own no-progress rule kills a worker at 24 looking-only calls with nothing written; both runs
  had made over 100.
- **The read pause here lifts permanently after the first write**, where SwarmCoder's resets its
  counter. The difference does not matter for what was measured, which is the first write.
- **The plain harness runs each shell command in a fresh subprocess**, so `cd` does not persist.
  Every variant lost a few turns to discovering that. It affects all of them equally.

## The help desk: variants 6 and 7

The owner's requirement was that a worker must be able to call for help rather than spend its
budget trying. Two tools — `ask_expert(question, what_i_tried)` and `request_skeleton(type_or_task)`
— answered by the system, plus a refusal that makes "must ask" mechanical: `javap`, `jar tf`,
`jar xf`, an `unzip` of a jar and a disk-wide hunt for one are refused with "call ask_expert
instead", and the refusal counts as an investigation call.

**v6 — with the brief, the tools go unused.** 38 turns, done, green. `ask_expert` called zero
times, `request_skeleton` zero times, zero commands refused. The worker never reached for either
because the answer was already in its prompt. That is the correct outcome and not a
disappointment: the measure of a help desk is not how often it is called.

**v7 — with no brief at all, the tools are what is left.** No worked example, no skeleton, nothing
but the two requirement documents, the reference folders and the ability to ask. It went green:
90 turns, 41 minutes, compiles, three acceptance tests passing, the store dependency added, the
stack's own persistence idiom, `com.zeroz4j.ui.*` on the client, none of the seven forbidden
technologies.

It also produced the most useful single fact in the whole experiment. It asked **one** question,
and it was exactly the right one:

> How do I persist a root object with EclipseStore through zerozstack-store-eclipsestore in a
> ZeroZ Stack server module? … (2) how to save a root object and how to save nested objects
> explicitly … (4) whether the server module needs a Maven dependency …

and the desk answered **nothing**. Every key term in it was an artifact name or a concept, never a
type name, so the call-site search had nothing to look for. That failure is what the desk was
rebuilt around: it no longer classifies questions at all. See the corpus below.

### What it costs to have no brief

| | v3 (example + skeleton) | v5 (example only) | v7 (nothing) |
|---|---|---|---|
| turns | 34 | 49 | 90 |
| wall | 13 min | 22 min | 41 min |
| green | yes | yes | yes |

All three reach the same place. The brief is what makes it **fast**; the forcing rule is what makes
it **happen**; the help desk is the floor under a worker whose brief did not cover what it hit.
