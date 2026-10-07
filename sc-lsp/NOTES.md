# sc-lsp — implementation notes

`LspService` is the facade over language servers (spec §S6). LSP4J and the wire protocol are
**confined to this module** — `LspBoundaryTest` (ArchUnit, in sc-app) fails the build if any
class outside `com.swarmcoder.lsp` references `org.eclipse.lsp4j`, exactly like Koog/tree-sitter.

History and measurements: `docs/DEVELOPER_CORRECTIONS.md` section 55 (2026-10-04).

## Design contract

- **Enhancer, never a prerequisite.** Every method degrades to a safe empty result or a
  `NOT_AVAILABLE` answer with the reason, and NEVER throws. The real compile stage is always the
  authority — the server runs no annotation processor and no codegen, so its diagnostics are
  **advisory only**.
- **Answers are places.** `LspResult`: a sentence plus at most 40 `LspHit`s (`path:line` and one
  line of text). Never file contents.
- **Symbols are named as the tree queries name them:** `Type`, `com.example.Type`, `Type#member`,
  `Type#member(String, int)` for one overload.
- **It never imports a build.** Maven and Gradle import are off; the server opens a plain
  Eclipse project that `JdtLanguageServer` writes into its own data folder, with links to the
  checkout's source folders and the jars the caller hands in. Nothing of the project is executed
  on the PC and nothing is written into the checkout. Do not turn the importers back on.
- `LspService.UNAVAILABLE` is the default no-op.
- `JdtLanguageServer` launches lazily (on the first query) and shuts the process down on
  `close()`; `pid()` and `coldStartMillis()` say what was started.

## Where the product is

`JdtLsInstall`: `-Dswarmcoder.jdtLsHome`, then `tools.jdtLsHome` in `config.yaml`, then the newest
version folder under `~/.swarmcoder/tools/jdtls` (e.g. `1.61.0`). A product home contains
`plugins/org.eclipse.equinox.launcher_*.jar` and a per-OS `config_win` / `config_linux` /
`config_mac` folder. `-Dswarmcoder.jdtLs=off` acts as if none were installed (the unit tests run
that way).

To install another version: download `jdt-language-server-<version>-<stamp>.tar.gz` from
`download.eclipse.org/jdtls/milestones/<version>/`, check it against the `.sha256` beside it, and
extract it to `~/.swarmcoder/tools/jdtls/<version>`. It needs a JDK 21 or newer to run.

## Stores

`-data` and a private copy of the configuration live under `~/.swarmcoder/jdtls-data/<name>-<hash>`
for a project (kept, so the next start is warm) and under `.../checkouts/` for a worker's checkout
or the integration worktree (deleted on `close()`).

## Tests

- `JdtLanguageServerLiveTest` — a real server on a small Maven fixture: every query, rename,
  organize imports, and that nothing is written into the workspace. Skipped when no JDT LS is
  installed. Prints `JDTLS-TIMING` lines.
- `LanguageServerOnARealProjectLiveTest` (sc-knowledge) — the same through `LanguageQueries` on a
  checkout named by `SWARMCODER_LIVE_PROJECT`; run with `-Dswarmcoder.jdtLs=on`.
- `LspFacadeTest`, `JdtLanguageServerTest` — everything that needs no server.
- `-Dswarmcoder.jdtLs.trace=true` prints the server's own log lines on standard error.

## Who uses it

- Planning roles and the expert: `ExpertTools` through `LanguageQueries` (sc-knowledge), one
  read-only server on the project checkout.
- Workers: read-only questions go to the project's server; `problems_in`, `rename_symbol` and
  `organize_imports` to a server on the worker's own checkout (`ApiLookup.inCheckout`).
- Design checks: `LibraryTypes.withJarMembers` (a contract naming a type in a library jar).
- Verifier: the advisory `LspPrecheck` at FINAL_INTEGRATION, as before.

## Not built

Change signature, extract method, move type (they need JDT LS's own `java/getRefactorEdit`
requests). A source folder created after the server started is not seen until it restarts.
