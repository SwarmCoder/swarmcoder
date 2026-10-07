# SwarmCoder — rules for anyone (human or AI) working on this repository

Read `docs/DEVELOPER_CORRECTIONS.md` for history. This file holds the rules that must not be broken
again.

## 1. Agents learn a project from the syntax tree and object graph, not from raw files

This is a main design criterion of the product, set by the owner from the start. SwarmCoder parses
the target project into a lossless syntax tree and a Java object graph (types, members, relations,
usages) and keeps a search index. **Every agent role must get its knowledge of the project from
those, through deterministic (non-AI) lookup tools.** Reading whole source files and dumping
text-search results into a model's conversation is the last resort, not the default.

Order of preference for any role (architect, planner, design reviewer, test author, expert, judge,
worker):

1. A project map generated from the tree, given in the role's opening.
2. Tree and graph queries: a type's shape as the compiler resolves it, who uses a type or method,
   which files use several types together, what a module contains, what a build file declares, the
   body of ONE method or ONE type.
   The Eclipse JDT Language Server (installed under `~/.swarmcoder/tools/jdtls`) belongs to this
   step: supertypes and implementations, callers and callees, symbol search, outlines, docs, and
   the members of library-jar types the tree cannot list. Workers also change code through it
   (rename, organize imports) and ask it whether a file compiles, instead of rewriting files by
   hand and waiting for a full build. One tool per question: the tree and the language server must
   never give a role two different answers. The language server never imports or runs a build.
3. Short, ranked search hits with `path:line`.
4. A whole file, only when a body is needed that no query can return.

Rules that follow:

- **No AI where a lookup will do.** Fetching a method body, a type's members, a module's contents or
  a build declaration is a deterministic tool, never a model call and never an "expert" question.
- **A missing query is a bug in the tools, not a reason to read files.** If a role needs something
  the tree cannot return, add the query.
- **No prompt-stuffing.** Do not paste files, reference material or inventories into a role's
  opening to save it a lookup; give it the map and the tools.
- **A new role or a new tool is not done until it uses the tree.** Review any change to agent tools
  or prompts against this section.
- **Every run report shows, per role, lookups by kind** (tree/graph, search, whole file) with counts
  and characters returned, and prompt tokens per call. A role whose input is mostly search and
  whole files is a defect to fix before the next run.

### The regression this section exists for (found 2026-10-04)

For about 80 live runs the planning roles ignored the tree. Measured in runs 79 and 80, characters
returned to each role by lookup kind:

| role | text search | whole files | tree / graph queries |
|---|---|---|---|
| architect | 121,700 (20 calls) | 43,852 (18) | 2,210 (2) |
| planner | 154,176 (13) | 67,883 (25) | 4,979 (7) |
| test author | 106,041 (8) | 53,670 (19) | 5,405 (6) |
| expert | 30,252 (5) | 2,528 (1) | 0 |

One search answer was about 12,000 characters; one tree answer about 900. Each role re-read the same
project separately, every call resent it all, and one 783-line story cost about 2.7 million paid
cloud tokens (11.3 million before the first fixes). Causes: the tree queries were offered with the
same standing as search and file reads, no map from the tree was given at the start, the tree had no
query for a method body, a module's contents or a build file, and nobody measured what the roles
actually used. It went unnoticed because the model was local and its tokens were free, and because
the coordinating session (Claude) tracked run time and delivery instead of tokens and tool use.

## 2. Tokens are money

Planning roles may run on a paid cloud model. Report and judge runs by tokens per role, not by
minutes. Do not cap what a role may ask or look up; cut what each call costs.

## 3. Standing constraints

- Never print `~/.swarmcoder/config.yaml` (it holds a paid key).
- All model-written code runs in containers; no access to the PC's drives.
  The JDT language server is the one agreed exception to "in containers" (owner, 2026-10-04): it
  runs on the PC for roles and workers, because it only parses code, never imports or runs a build,
  and its edits are refused outside the worker's own checkout. Keep it that way.
- Persisted enums are append-only.
- No fixes that match particular English words, and no project-specific patches.
