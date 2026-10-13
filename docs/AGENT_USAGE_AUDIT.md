# What every agent role did, call by call: an audit of one five-story build

Audit of 2026-10-13. Nothing in the product was changed. No model was called. Every figure comes
from the stored sessions of one instance, read from a copy of its store by
`WhatEveryRoleDidTest` (sc-knowledge, test sources), and from that instance's log by text search.

## 1. What was measured, and how far to trust each figure

**The build.** A small three-module demo project (shared types, a server, a browser client), five
stories, built from 2026-10-10 to 2026-10-13 on one local model server with one model for every
role. Ten runs: five delivered, five ended early. The five stories added 1,533 lines to the demo
project, 714 of them main Java code.

**The data.** 278 stored sessions with 4,065 model calls and 87.2 million tokens. The usage ledger
of section 93 holds no row for any of the ten runs, so everything is taken from the stored events
of each session.

| figure | exact or estimated |
|---|---|
| calls, sessions | exact |
| tokens of a call (input and output together) | exact: the server's count, kept on every stored answer |
| output tokens, thinking | estimated: the thinking as the server reported it, plus text and tool arguments at four characters a token |
| input tokens | call size minus the estimated output; output is 3.9% of all tokens, so the error is small |
| time in calls | estimated: time from the stored event before an answer to the answer; it includes waiting for a place on the server |
| characters a lookup returned | exact |
| tokens a lookup result cost by being sent again | estimated: characters / 4 times the later calls of the session. "Least" stops at the first call that is 4,000 tokens smaller than the one before (a tidy); "most" runs to the session's end |
| what a request was made of | estimated: the stored calls replayed through the product's own tidy with today's marks (20,000 / 8,000 for roles, 6,000 / 1,500 for the expert, 12,000 / 3,000 for workers) |
| which story a planning session belongs to | estimated: a planning session names no run; it is given to the run that had started last when it opened |
| reference material | a lookup under one of the reference roots, or of a type outside the project's own package |

A "target" is the type, file, document or module a lookup names. A search in words is its own
target.

## 2. The roles the product uses

Found from the code, not from a list.

| role | what it is for | when it runs | leaves a stored session |
|---|---|---|---|
| architect | designs how a story's fixed criteria are met; keeps findings for workers | one session per run, continued when a check or reviewer sends the design back (limit 120 turns) | yes |
| design reviewer | checks the design against the project's standing rules | one session per reading, 2 to 4 readings a run in this build | yes in "lookups" mode (this build); one question and no session in the default mode since section 96 |
| test reviewer | checks the acceptance tests against the standing rules | one session per reading, 1 to 4 a run | as above |
| task planner | splits the design into tasks and orders them | one session per run | yes |
| test author, writing | writes the acceptance tests and browser journeys for the tasks | one session per task that needs tests, continued when a test is sent back | yes |
| test author, repair | corrects tests the build sent back | when tests fail for a reason of their own | yes |
| test author, journey review | after a browser journey fails on the built story, decides whether the journey or the screen is wrong | one short session per failed journey (limit 10 turns) | yes |
| expert (help desk) for a worker, the architect or the test author | answers one question with read-only lookups | per question that no stored answer settles (limit 12 turns); a second "closing" session of 2 turns writes an answer when the first ran out | yes |
| answer check | decides whether a stored answer already settles a question | once per question, before the expert | no: one question |
| worker, first round | writes the code of one task | 4 candidates a task | yes |
| worker, repair round | writes the task again from a failed candidate, with what failed | 2 or more candidates per round | yes; same stored role name as a first-round worker, told apart only by its index (100 and up) |
| judge | scores a candidate that passed verification | one question per candidate judged | no |
| requirements analyst, story planner, chat, image reading | the Console's wizards and chat | per user action | no |
| knowledge extraction (rules, guidelines, primer) | reads a project's documents once at import | per project | no |
| librarian, approver | choose a worked example; approve an action | - | no model call was found in either |

The roles that leave no session are in section 9.

## 3. The whole build in one table

Tokens in millions (calls). Exact.

| role | S1 | S2 | S3 | S4 | S5 | all five | share |
|---|---|---|---|---|---|---|---|
| architect | 7.25 (158) | 0.38 (17) | 1.03 (46) | 1.02 (32) | 1.24 (44) | 10.92 (297) | 12.5% |
| design reviewer | 1.59 (107) | 0.30 (21) | 0.43 (36) | 0.21 (18) | 0.22 (19) | 2.74 (201) | 3.1% |
| task planner | 0.33 (21) | 0.17 (10) | 0.13 (9) | 0.14 (8) | 0.81 (41) | 1.59 (89) | 1.8% |
| test author, writing | 8.95 (256) | 0.38 (17) | 0.71 (31) | 0.46 (18) | 7.76 (130) | 18.25 (452) | 20.9% |
| test author, repair | - | - | 0.48 (17) | - | - | 0.48 (17) | 0.5% |
| test author, journey review | 0.06 (6) | 0.05 (4) | 0.02 (2) | 0.35 (21) | - | 0.48 (33) | 0.6% |
| test reviewer | 2.44 (111) | 0.15 (9) | 0.52 (36) | 0.20 (14) | 0.94 (48) | 4.24 (218) | 4.9% |
| expert, all askers | 5.51 (412) | - | 0.21 (14) | - | 0.45 (24) | 6.17 (450) | 7.1% |
| worker, first round | 12.90 (712) | 2.02 (125) | 14.21 (746) | 2.31 (146) | 0.88 (60) | 32.32 (1,789) | 37.1% |
| worker, repair round | 4.13 (256) | - | 3.38 (168) | 2.47 (95) | - | 9.97 (519) | 11.4% |
| **story** | **43.16 (2,039)** | **3.45 (203)** | **21.11 (1,105)** | **7.15 (352)** | **12.30 (366)** | **87.16 (4,065)** | |

By run:

| story | run | ended | sessions | calls | tokens |
|---|---|---|---|---|---|
| S1 | 1 | early | 74 | 998 | 19.00 M |
| S1 | 2 | early | 27 | 374 | 8.22 M |
| S1 | 3 | early | 20 | 264 | 6.24 M |
| S1 | 4 | delivered | 28 | 403 | 9.70 M |
| S2 | 1 | delivered | 15 | 203 | 3.45 M |
| S3 | 1 | early | 25 | 377 | 6.80 M |
| S3 | 2 | delivered | 41 | 728 | 14.31 M |
| S4 | 1 | delivered | 27 | 352 | 7.15 M |
| S5 | 1 | early | 11 | 186 | 6.13 M |
| S5 | 2 | delivered | 10 | 180 | 6.17 M |

**46.4 million tokens, 53% of the build, went to the five runs that ended early.** The log shows one
of the five ending itself; the application was started fifteen times in the three days and the
other four runs have no ending in the log. Why each start happened is not recorded.

**57,000 tokens per line added** to the demo project; 122,000 per line of main code.

**Where a token goes, whatever the role** (estimated): 96% of all tokens are input. About 26.0
million (30%) are the openings sent again on every call. At least 24.5 million (28%) are lookup
results, first sent and then sent again. Output is 3.4 million, 78% of it thinking.

## 4. Measured per role

Exact unless marked (e) for estimated. Call sizes are input and output together.

| role | sessions | calls | tokens | a call: median | largest | first call of a session, median | output (e) | of it thinking (e) | minutes in calls (e) |
|---|---|---|---|---|---|---|---|---|---|
| architect | 12 | 297 | 10,919,389 | 32,058 | 101,627 | 7,670 | 368,819 | 289,272 | 288 |
| design reviewer | 22 | 201 | 2,736,400 | 12,265 | 35,730 | 7,249 | 156,890 | 139,398 | 107 |
| test reviewer | 22 | 218 | 4,241,881 | 16,408 | 56,064 | 7,261 | 214,378 | 202,248 | 164 |
| task planner | 11 | 89 | 1,585,395 | 16,814 | 42,930 | 10,802 | 210,474 | 180,853 | 140 |
| test author, writing | 16 | 452 | 18,251,748 | 34,009 | 131,032 | 12,612 | 925,464 | 704,013 | 823 |
| test author, repair | 1 | 17 | 478,849 | 30,341 | 51,321 | 11,633 | 71,281 | 69,600 | 48 |
| test author, journey review | 8 | 33 | 483,332 | 12,789 | 29,901 | 10,867 | 62,747 | 54,128 | 41 |
| expert for a worker | 29 + 12 closing | 348 + 22 | 5,152,956 | 12,493 | 34,206 | 9,268 | 121,494 | 69,052 | 275 |
| expert for the test author | 5 + 2 closing | 62 + 4 | 891,652 | 11,475 | 28,606 | 5,255 | 34,894 | 22,179 | 39 |
| expert for the architect | 1 + 1 closing | 13 + 1 | 125,635 | 9,389 | 12,817 | 4,552 | 4,662 | 2,189 | 3 |
| worker, first round | 100 | 1,789 | 32,322,240 | 17,539 | 39,724 | 9,029 | 648,458 | 444,101 | 1,316 |
| worker, repair round | 36 | 519 | 9,973,420 | 18,163 | 52,454 | 10,021 | 548,069 | 459,498 | 767 |

### What the calls were for

Each call is put in one group from the tools it called, and nothing else. Share of the role's
tokens (calls).

| role | learning the reference material | reading the project | reading its own earlier output | drafting or editing | checking a draft | revising after an objection | running builds or tests | waiting on a question | handing in | no tool call |
|---|---|---|---|---|---|---|---|---|---|---|
| architect | 41% (112) | 30% (122) | - | 13% (25) | 7% (14) | 4% (8) | - | 0% (1) | 4% (10) | 1% (5) |
| design reviewer | 51% (96) | 34% (83) | - | - | - | - | - | - | 15% (22) | - |
| test reviewer | 64% (124) | 24% (72) | - | - | - | - | - | - | 12% (22) | - |
| task planner | 15% (13) | 45% (48) | - | - | 17% (13) | - | - | - | 14% (11) | 9% (4) |
| test author, writing | 32% (140) | 18% (134) | - | 0% (1) | 21% (91) | 17% (44) | - | 1% (8) | 3% (14) | 7% (20) |
| test author, journey review | 25% (9) | 9% (4) | - | - | 32% (10) | - | - | - | 18% (7) | 16% (3) |
| expert for a worker | 47% (172) | 40% (143) | - | - | - | - | - | - | 7% (17) | 6% (16) |
| worker, first round | 11% (211) | 33% (634) | 4% (70) | 17% (315) | 4% (83) | - | 23% (371) | 2% (30) | 4% (67) | 1% (8) |
| worker, repair round | 10% (62) | 45% (257) | 3% (8) | 7% (29) | 5% (23) | - | 24% (112) | 2% (10) | 3% (13) | 1% (5) |

A first-round worker spends 48% of its tokens looking things up, 23% building and testing and 17%
writing. A repair worker spends 58% looking things up and 7% writing.

### Lookups by kind

Calls / characters returned. The last two columns are the share of all characters that is about
the reference material, and the tokens the results cost by being sent again (least).

| role | tree and graph | language server | document sections | search | whole files | parts of files | folder listings | shell reads | reference share | carried (least) |
|---|---|---|---|---|---|---|---|---|---|---|
| architect | 322 / 463,080 | 155 / 98,054 | 113 / 203,045 | 46 / 48,879 | 135 / 411,125 | - | 16 / 5,935 | - | 60% | 5.07 M |
| design reviewer | 194 / 140,421 | 60 / 42,354 | 110 / 157,517 | 26 / 47,411 | 81 / 255,755 | 2 / 9,216 | 7 / 3,376 | - | 61% | 1.11 M |
| test reviewer | 77 / 85,164 | 50 / 31,832 | 246 / 454,038 | 17 / 35,896 | 79 / 361,803 | - | 8 / 3,310 | - | 68% | 2.38 M |
| task planner | 128 / 107,621 | 23 / 16,376 | 9 / 7,025 | 2 / 5,206 | 30 / 124,489 | - | 5 / 1,582 | - | 26% | 0.36 M |
| test author, writing | 200 / 131,538 | 176 / 78,216 | 53 / 146,112 | 127 / 308,969 | 94 / 376,941 | 9 / 14,718 | 57 / 29,145 | - | 62% | 4.51 M |
| expert for a worker | 214 / 192,692 | 217 / 171,286 | 51 / 361,734 | 128 / 188,422 | 32 / 157,353 | 5 / 7,104 | 30 / 23,878 | - | 72% | 1.65 M |
| worker, first round | 853 / 679,849 | 221 / 171,946 | 59 / 133,242 | 90 / 387,120 | 265 / 593,472 | 27 / 28,311 | - | 145 / 115,727 | 31% | 6.80 M |
| worker, repair round | 299 / 254,227 | 47 / 29,171 | 9 / 29,648 | 39 / 160,321 | 133 / 271,968 | 13 / 18,916 | - | 76 / 58,533 | 29% | 2.00 M |

Workers also read their task's acceptance test 175 times (511,544 characters; 1.75 million tokens
carried), about once a session.

Whole files are a third of what the architect, the test author and both reviewers were sent, and a
quarter of what a worker was sent. A worker's 398 whole-file reads are almost all of the project's
own files. All roles together: whole files cost at least 6.4 million tokens, document sections 3.7
million, search 3.4 million. (The whole-file answers of section 95 came after most of this build.)

### Repeats

Lookups / tokens carried (least). Each lookup is counted once, in the first column that applies.

| role | first time | again in the same session | a sibling candidate of the same task | another session of the same role, same run | another role in the same run | an earlier run of the same story | an earlier story |
|---|---|---|---|---|---|---|---|
| architect | 214 / 1.80 M | 262 / 1.53 M | - | - | - | 176 / 1.31 M | 135 / 0.43 M |
| design reviewer | 75 / 0.20 M | 111 / 0.21 M | - | 89 / 0.17 M | 161 / 0.46 M | 31 / 0.05 M | 13 / 0.02 M |
| test reviewer | 90 / 0.54 M | 157 / 0.90 M | - | 57 / 0.26 M | 145 / 0.51 M | 9 / 0.09 M | 19 / 0.09 M |
| task planner | 10 / 0.01 M | 66 / 0.12 M | - | 16 / 0.08 M | 91 / 0.14 M | 4 / 0.00 M | 10 / 0.01 M |
| test author, writing | 213 / 1.97 M | 185 / 0.74 M | - | 78 / 0.31 M | 181 / 0.91 M | 43 / 0.19 M | 16 / 0.39 M |
| expert for a worker | 184 / 0.57 M | 122 / 0.19 M | - | 244 / 0.56 M | 116 / 0.31 M | 8 / 0.01 M | 3 / 0.02 M |
| worker, first round | 345 / 1.50 M | 473 / 1.69 M | 621 / 2.60 M | 65 / 0.21 M | 191 / 0.49 M | 40 / 0.13 M | 48 / 0.18 M |
| worker, repair round | 140 / 0.42 M | 152 / 0.43 M | 302 / 0.97 M | 3 / 0.00 M | 45 / 0.11 M | 13 / 0.04 M | 13 / 0.03 M |

- **Only 1,345 of 6,031 lookups (22%) were the first time anybody in the build asked about that
  target.**
- "Again in the same session" is mostly a different question about the same type (its shape, then
  one body). The very same call made twice in a session is small: 356 calls, 1.07 million tokens
  for all roles. The "unchanged" answer works.
- Of the repeats within a session, the replay puts 85 to 90% while an earlier answer about the
  target was still whole in the conversation, and 10 to 15% after a tidy had shortened every
  earlier answer (architect 28 of 262, test author 26 of 185, first-round workers 55 of 473,
  expert 15 of 122). The tidy is not what drives the re-asking.

### What a request carried

Characters of an average request, from the replay. Estimated.

| role | system text | rest of the opening (map, rules, task, findings) | results still whole | results shortened | its own text and drafts | results of its own tools | all | opening sent again, tokens | share of the role's tokens |
|---|---|---|---|---|---|---|---|---|---|
| architect | 7,751 | 10,466 | 54,113 | 3,876 | 7,021 | 965 | 84,192 | 1.34 M | 12% |
| design reviewer | 5,116 | 10,799 | 18,738 | 6 | 1,097 | - | 35,756 | 0.78 M | 28% |
| test reviewer | 4,717 | 10,226 | 34,687 | 1,113 | 1,077 | - | 51,820 | 0.82 M | 19% |
| task planner | 10,854 | 18,190 | 17,177 | 2 | 2,173 | 139 | 48,535 | 0.67 M | 42% |
| test author, writing | 15,174 | 19,333 | 42,258 | 3,566 | 24,555 | 3,886 | 108,772 | 4.18 M | 23% |
| expert for a worker | 3,392 | 18,067 | 16,240 | 292 | 669 | - | 38,660 | 1.87 M | 39% |
| worker, first round | 26,634 | 112 | 16,558 | 555 | 4,543 | 5,025 | 53,427 | 11.66 M | 36% |
| worker, repair round | 29,947 | 112 | 16,823 | 383 | 6,383 | 2,670 | 56,318 | 3.91 M | 39% |

**Is the opening the same text for many roles?** Largely. Taking one session of each role and
comparing blocks of 200 characters or more word for word: 13,087 of the architect's 18,855
characters are also in other roles' openings, 13,087 of the planner's 30,569, 14,953 of the test
author's 34,864, 9,218 of each reviewer's, 13,022 of a first-round worker's 21,253 and 18,757 of a
repair worker's 25,210. The same blocks appear in up to nine roles. Each session reads them cold.
First-round workers had 80 distinct system texts over 100 sessions: candidates of one task share
theirs, as section 0 of CLAUDE.md intends.

On top of the conversation every call carries the tool descriptions. A session's first call is
2,500 to 3,400 tokens larger than its opening (estimated), which is about 11 million tokens over
the build.

## 5. What came of each role's sessions, and its three largest wastes

### Architect

- **Outcome.** 12 sessions, 10 designs. 5 sessions (5.28 million tokens) are in runs that
  delivered; 7 (5.64 million) are in runs that ended early and whose design was not built.
- **Wastes.**
  1. 5.64 million tokens designing in runs that ended early. A new run of the same story starts
     its design from nothing.
  2. 1.74 to 2.52 million tokens on 311 lookups of targets an earlier run or story had already
     looked up. (`known_about` of section 97 was added after this build.)
  3. 1.38 million tokens carrying 135 whole files. Three listings of a reference root, at about
     30,000 characters each, cost a further 0.44 million (the architect and one other role).

### Design reviewer and test reviewer

- **Outcome.** 44 readings, 6.98 million tokens. 41 approved; 3 objected (2 designs, 1 test
  set). Each reading is a new session that learns the project again.
- **Wastes.**
  1. 6.98 million tokens for three objections. 85% of a reviewer's tokens is looking things up.
  2. 1.39 million tokens (least) on 452 lookups of targets another session of the same run had
     looked up, 146 of them an earlier reading by the same reviewer.
  3. 4.33 million tokens in runs that ended early.

  Section 96 has since made a reading one question by default. This build shows what the session
  form cost.

### Task planner

- **Outcome.** 11 sessions, 6 in runs that delivered. 27 tasks stored; 16 have a selected
  candidate.
- **Wastes.**
  1. 0.67 million tokens (42% of the role) is its 30,000-character opening sent on every call.
  2. 0.58 million tokens in runs that ended early.
  3. 0.81 million tokens on S5, where the plan was "nothing to build".

### Test author, writing

- **Outcome.** 16 sessions. 6 (5.50 million tokens, 30%) are in runs that delivered. The largest
  single call of the build is here: 131,032 tokens.
- **Wastes.**
  1. 12.75 million tokens in runs that ended early.
  2. 7.76 million tokens on S5 (130 calls in two sessions), a story whose result was "already
     satisfied by the existing implementation, no changes".
  3. 2.87 million tokens carrying its own earlier text, drafts and compile results (25,424
     characters of an average request). A draft is sent whole to the compile check, and every
     earlier draft stays in the conversation. A further 1.26 million went on 20 calls that called
     no tool and were answered with "continue using your tools".

### Test author, journey review and repair

- **Outcome.** 8 journey reviews: 3 corrected the journey, 4 said the screen was wrong, 1 ran out
  of turns (194,099 tokens). The one repair session was kept.
- **Wastes.** Small role. The one review that ran out of turns is 40% of its tokens.

### Expert (help desk)

- **Outcome.** The stored sessions hold 49 questions (40 from workers, 8 from the test author, 1
  from the architect). The log shows 51 checks against stored answers, 6 of which settled the
  question. 35 investigations ran.
  15 of the 35 ran out of their 12 turns and needed a closing session. **No worker that asked a
  question was selected or passed**: all 40 worker questions came from candidates that failed or
  were stopped. 13 of the 40 were followed by writing or checking within five calls. Workers
  waited 6.5 hours in all for answers (estimated).
- **Wastes.**
  1. 3.08 million tokens in the 15 investigations that ran out of turns and their closing
     sessions.
  2. 0.87 to 1.07 million tokens on 360 lookups another expert session or another role had
     already made in the same run. Each question starts from nothing.
  3. 1.87 million tokens is the opening sent again (39%); the question's context is 18,000
     characters of it.

### Worker, first round

- **Outcome.** 100 sessions, 32.3 million tokens.

  | what came of the candidate | sessions | tokens | share |
  |---|---|---|---|
  | selected | 13 | 4.83 M | 15% |
  | passed, not chosen | 23 | 6.14 M | 19% |
  | stopped because a sibling had passed | 22 | 7.11 M | 22% |
  | failed verification | 29 | 7.97 M | 25% |
  | stopped: no progress | 5 | 2.76 M | 9% |
  | stopped for other reasons, or archived | 8 | 3.52 M | 11% |

- **Wastes.**
  1. 11.66 million tokens is the 26,000-character opening sent on all 1,789 calls.
  2. 2.60 to 3.36 million tokens on 621 lookups a sibling candidate of the same task also made.
  3. 3.74 million tokens (both rounds) on 183 calls that 23 candidates began after a sibling of
     their task had already finished and passed. This is an upper bound: a passing candidate is
     only known to have passed once its verification ends.

### Worker, repair round

- **Outcome.** 36 sessions, 9.97 million tokens. 3 selected (1.91 million, 19%); 14 stopped when
  the round's time was up (2.40 million); 15 failed (4.40 million).
- **Wastes.**
  1. 6.80 million tokens in the 29 sessions that failed or were stopped by the clock.
  2. 3.91 million tokens is the 30,000-character opening sent again.
  3. 5.79 million tokens (58%) looking things up, 0.70 million (7%) writing. A repair worker
     starts with the failed candidate's findings and still reads the project again.

### How full the server was

Estimated from the stored times of the calls. The ceiling measured for this server is four.

| run | hours | no call running | calls at once while any ran | time at 1 / 2 / 3 / 4 or more |
|---|---|---|---|---|
| S1 run 1 | 8.6 | 7% | 2.04 | 32% / 43% / 10% / 13% |
| S1 run 2 | 3.7 | 17% | 1.53 | 63% / 26% / 4% / 5% |
| S1 run 3 | 3.0 | 17% | 1.85 | 52% / 24% / 6% / 15% |
| S1 run 4 | 3.9 | 8% | 2.02 | 48% / 23% / 4% / 22% |
| S2 | 1.9 | 11% | 1.75 | 67% / 8% / 6% / 17% |
| S3 run 1 | 3.9 | 17% | 2.24 | 38% / 22% / 14% / 23% |
| S3 run 2 | 7.1 | 33% | 2.12 | 50% / 15% / 5% / 28% |
| S4 | 5.8 | 23% | 1.59 | 54% / 35% / 5% / 4% |
| S5 run 1 | 2.6 | 6% | 1.13 | 94% / 0% / 1% / 3% |
| S5 run 2 | 4.2 | 5% | 1.00 | 100% / 0% / 0% / 0% |

In seven of the ten runs the server had a single conversation for half or more of the busy time.
Planning is the reason: the architect, the planner, the test author and the reviewers run one
after another, and they are 44% of the build's tokens.

## 6. The same facts established by several roles

300 targets were named by a lookup in the whole build. 101 of them were looked up by three roles
or more: 3,460 lookups, 5.3 million characters, at least 13.8 million tokens carried.

| what | lookups | sessions | roles | stories | characters | tokens carried (least) |
|---|---|---|---|---|---|---|
| the modules' build files | 105 | 51 | 7 | 4 | 523,135 | 1.30 M |
| the client's main screen class | 210 | 65 | 8 | 5 | 492,307 | 1.20 M |
| the service implementation | 238 | 104 | 9 | 5 | 294,966 | 1.00 M |
| the outline of the reference documents | 53 | 44 | 9 | 5 | 505,844 | 0.99 M |
| the first acceptance test class | 81 | 59 | 9 | 5 | 277,791 | 0.72 M |
| the server's query class | 163 | 67 | 7 | 4 | 190,220 | 0.46 M |
| the reference guide on testing | 84 | 23 | 6 | 5 | 130,715 | 0.45 M |
| the shared data type | 211 | 142 | 10 | 5 | 137,372 | 0.44 M |
| what the server module contains | 147 | 113 | 10 | 5 | 147,055 | 0.39 M |
| the verification settings file | 32 | 31 | 8 | 5 | 126,403 | 0.38 M |
| the reference guide on persistence | 83 | 26 | 6 | 4 | 99,253 | 0.35 M |
| the service interface | 213 | 129 | 9 | 5 | 93,466 | 0.29 M |
| a framework storage type | 60 | 38 | 10 | 2 | 105,082 | 0.23 M |
| a framework database type | 69 | 52 | 11 | 4 | 72,456 | 0.20 M |

The shape of a framework type does not change during a build, and the outline of the reference
documents does not change at all. Both were fetched again by up to eleven roles.

## 7. The ten changes that would save the most

Savings are computed from this build. They overlap and must not be added up. None is a cap on what
a role may ask, and none is specific to a project, a framework or a wording.

| # | change | roles | saving computed here | risk | needs a design decision |
|---|---|---|---|---|---|
| 1 | A run of a story that follows one that ended early starts from that run's stored design, plan and tests when the requirements have not changed | architect, planner, test author, reviewers | up to 23.4 M: the planning tokens of the five runs that ended early | a faulty design is carried on; needs a rule for when to start afresh | yes |
| 2 | Find out that a story's criteria are already met before the tests are written and the swarm is planned | all planning roles | up to 12.3 M: all of S5, whose result was "no changes" (6.1 M of it is also in change 1) | a false "already met" skips real work | yes |
| 3 | Shorten the workers' opening: move the blocks it shares word for word with other roles behind a lookup | workers | 8.3 M (13,022 characters on 1,789 calls and 18,757 on 519); ceiling 15.6 M. Each 1,000 characters removed is 0.58 M | a worker that must look a rule up may not; a local server reads a repeated opening from its cache, so the gain in time is smaller than in tokens | yes |
| 4 | One store of established findings per project, read by every role and written by every lookup session (`known_about` for all roles, not only the architect) | all | 7.8 M to 11.1 M: lookups of a target another session of the run, an earlier run or an earlier story had already looked up | a stale finding after the code changed; a finding still costs room | yes |
| 5 | Shorten old build output and superseded drafts as old lookup results are shortened | test author, workers, architect | up to 7.2 M: its own text, drafts and tool results carried on every call | the model loses a draft it wanted to go back to | no |
| 6 | The reviewer asks one question a reading (section 96, already the default) | reviewers | 6.6 M: 44 sessions against 44 single calls | a reviewer that cannot look anything up objects less well | decided |
| 7 | Candidates of one task share what the first of them looked up | workers | 3.6 M to 4.7 M: 923 lookups a sibling also made | candidates grow more alike; must stay on the right side of the no-stuffing rule | yes |
| 8 | Stop a candidate the moment a sibling of its task has passed verification, and give its place to the next ready task | workers | up to 3.7 M: 183 calls begun after a sibling had finished and passed | none for tokens; a place is only gained if work is ready | no |
| 9 | An expert investigation that runs out of turns answers in the same session from what it holds; a question reaches siblings of the task once | expert, workers | up to 3.1 M: the 15 investigations that ran out and their closing sessions | a forced answer from thin material misleads | yes |
| 10 | A turn that calls no tool is not answered by sending the whole conversation again | all | up to 2.5 M: 77 such calls | some are real final answers | no |

Not in the ten because section 95 already did it for the planning roles: answering a whole-file
read with an outline. For workers it is still open: 398 whole files, 2.0 million tokens.

## 8. The measuring itself

**What a person gets today for a normal application build.** Two read tools over MCP, `run_report`
and `project_report`. A run's report has one table per role (server, calls, input tokens, input
per call, output, cached input, seconds in calls), a totals sentence, one line of lookups by kind
per role, and `WARNING:` lines. The delivery evidence carries one totals sentence. A person can
read it without a specialist. The Console shows none of it.

**For this build it would have shown almost nothing.** None of the ten runs has a ledger row, so
each would report "not recorded call by call" with a rough table from its stored sessions.

**Missing against CLAUDE.md section 1's last rule** ("every run report shows, per role, lookups by
kind with counts and characters, and prompt tokens per call; a role whose input is mostly search
and whole files is a defect"):

| missing | effect |
|---|---|
| The warnings fire only for paid servers | a local build, like this one, is never warned, whatever a role does |
| Lookups are kept per role and kind only, not per task, worker or round | first-round and repair workers are one row; a role's lookups cannot be set against what came of its work |
| Sums only, no row per call | no median, no largest call in the table, no "what was this call for" |
| The thinking tokens reach the meter and are dropped by the ledger | thinking share is unknown in the report |
| Repeats are not counted | the finding of section 4 (22% first-time lookups) cannot be seen |
| Tokens are not joined to outcome | tokens of selected against unselected candidates, or of runs that ended early, are not shown |
| An expert's calls are filed under "expert for ..." and its lookups under "expert" | the two halves of one row never meet |
| The task planner and the Console's story planner share the name "planner" | one row for two roles |
| The per-kind lookup table exists in the code but the report prints only the one-line form | characters per kind are harder to read than they need be |
| A planning session's stored record names no run | `run_detail` does not list planning sessions at all |

## 9. What cannot be told from the stored data

| cannot be told | what the product would have to record |
|---|---|
| Tokens of the judge (33 calls in the log), the answer check (51), the Console's wizards and chat, and knowledge extraction | every one-question call in the ledger, per call, on every instance |
| Input and output apart; cached input; thinking as the server counts it | the server's three counts on every stored answer, not their sum |
| How long a call took apart from how long it waited for a place | start and end of each request |
| What a request really contained: which results were whole, what the tidy cut, the tool descriptions | the size of each part of a request, per call, and every tidy as a stored event (today it is a log line only) |
| Whether a worker's tree question was answered by the tree or the language server | the answering source on the stored tool result |
| Which run and story a planning session worked for | the run id on the session record |
| Why a run ended early, and who ended it | the reason and the actor on the run |
| Whether an expert's answer was used | the question's id on the asker's later edits, or at least which answer a worker had been given when its candidate was verified |
| Whether two questions to the expert were the same question | a stored key per question and which stored answer it was compared with |
| When a candidate was known to have passed | the time verification ended, on the candidate |
| Whether a reviewer's objection changed the design | the design revision each reading saw and the one that followed |
| How full the server was, exactly | the server's own count of conversations in flight; the application build has no concurrency section, only the harness has |

## 10. How to run the analysis again

Copy an instance's `store` and `blobs` folders to a temporary folder. Never point the test at a
live store.

```
SWARMCODER_SESSIONS_COPY=<the copy> \
SWARMCODER_SESSIONS_REPORT=<a file to write> \
SWARMCODER_SESSIONS_REFERENCEROOTS=<labels of the reference roots, comma separated> \
SWARMCODER_SESSIONS_PROJECTPACKAGE=<the project's own Java package> \
mvn -q -o -pl sc-knowledge -am test -Dtest=WhatEveryRoleDidTest \
    -Dsurefire.failIfNoSpecifiedTests=false
```

Without `SWARMCODER_SESSIONS_COPY` the test does nothing.
