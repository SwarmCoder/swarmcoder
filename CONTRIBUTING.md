# Contributing to SwarmCoder

First off, thank you for considering contributing to SwarmCoder!

## Code of Conduct

By participating in this project, you are expected to uphold standard professional conduct. Please be welcoming and respectful to all members of our community.

## How Can I Contribute?

### Reporting Bugs
If you find a bug in the source code or a mistake in the documentation, you can help us by submitting an issue to our GitHub Repository. Even better, you can submit a Pull Request with a fix.

### Suggesting Enhancements
If you have an idea for an enhancement, please submit an issue to our GitHub Repository.

### Pull Requests
1. Fork the repo and create your branch from `master`.
2. If you've added code that should be tested, add tests.
3. If you've changed behaviour, update the documentation.
4. Run the tests that cover your change (see below). Do not run a bare `mvn test`.
5. Make sure your code follows the existing formatting. All new source files should include the Apache 2.0 license header; copy it from an existing file.
6. Issue that pull request!

## Running tests

The full suite takes tens of minutes, and an interrupted run gives no verdict. Name what you run:

```bash
mvn -pl sc-workflow -am test -Dtest=SomeTest -Dsurefire.failIfNoSpecifiedTests=false
```

Tests that need Docker, a browser, a live model or a paid cloud model declare it with `@RunsWhen`
and are skipped, loudly, when what they need is absent. [docs/TESTING.md](docs/TESTING.md) has the
whole rule.

## The rules that must not be broken

[CLAUDE.md](CLAUDE.md) holds them, for human and AI contributors alike. In short:

- **Agents learn a project from the syntax tree and object graph, not from raw files.** A new agent
  role or tool is not done until it uses the tree. A missing query is a bug in the tools, not a
  reason to read files.
- **No AI where a lookup will do.**
- **Tokens are money.** Report and judge runs by tokens per role.
- **All model-written code runs in containers.**
- **Persisted enums are append-only.** Inserting a constant in the middle stops the application
  from starting.
- **No fixes that match particular English words, and no project-specific patches.**

## Write the changelog entry for the person upgrading

Every change a user can notice gets an entry under `## [Unreleased]` in
[CHANGELOG.md](CHANGELOG.md). Say what changed and what somebody has to do about it. A change that
can break an existing installation goes under **Breaking**, with what to do instead.

**One release, one entry.** When several branches land in the same release, the entries are merged
into a single set of sections before the release is cut, never left as one block per branch.

## License
By contributing, you agree that your contributions will be licensed under its Apache 2.0 License.
