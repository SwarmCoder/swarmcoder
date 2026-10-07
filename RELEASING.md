# Releasing SwarmCoder

How a maintainer cuts a release. Not needed to *use* SwarmCoder — see [README.md](README.md) and
[docs/](docs/) for that.

The procedure is the one ZeroZ Stack uses
([its RELEASING.md](https://github.com/ZeroZ4j/zerozstack/blob/main/RELEASING.md)), with one
difference: SwarmCoder is an application, not a library, so a release is a tag and a GitHub
release of the source. Nothing is published to Maven Central, and no built program is attached:
the application jar needs about 390 MB of third-party libraries beside it, so people build it
from source.

## Before a release

1. `mvn clean package` from the root — the whole build, tests included. This is the one time the
   full suite is run; it takes tens of minutes. Tests that need Docker, a browser or a live model
   are skipped when those are absent and the build says which ([docs/TESTING.md](docs/TESTING.md)),
   so run it on a machine that has Docker and Chromium.

   **`package`, not `install`.** Every module resolves its siblings from the reactor, so nothing is
   lost, and an `install` would write the number you are about to release into the shared local
   repository before the release exists.
2. Update [CHANGELOG.md](CHANGELOG.md). If several branches landed in this release, merge their
   entries into **one** set of sections first. Rename `## [Unreleased]` to the version and the
   date, then read the **Breaking** section as a user would.
3. Set the version. This build uses CI-friendly versioning: change `<revision>` in the root
   `pom.xml` and every module follows, with `flatten-maven-plugin` resolving it in the built POMs.
4. Correct the version where it is written out, because `${revision}` does not reach these:
   - `run.bat` (the name of the jar it starts)
   - `README.md` and `docs/USER_MANUAL.md` (the start command)
   - `docs/Agent-Knowledge-And-Operations.md` (the operating notes)

   `git grep -n "sc-app-"` finds all of them.
5. Check that every Java source file and every `pom.xml` carries the Apache 2.0 header:

   ```bash
   git ls-files 'sc-*.java' | grep -E '^sc-[^/]+/src/(main|test)/java/' | xargs grep -L "Licensed under the Apache License"
   ```

   It must print nothing.
6. Check that the build depends on no `-SNAPSHOT`: `git grep -n "SNAPSHOT" -- pom.xml 'sc-*/pom.xml'`
   must print nothing. Somebody who clones the repository cannot resolve a snapshot that only
   exists on the maintainer's machine.

## Publishing

Commit the release, then tag it and push both:

```bash
git tag -a v0.1.0 -m "SwarmCoder 0.1.0"
git push origin master
git push origin v0.1.0
```

Then create the GitHub release from the tag, with that version's changelog section as the notes:

```bash
gh release create v0.1.0 --title "SwarmCoder 0.1.0" --notes-file <the changelog section>
```

Tag only a commit whose full build passed — a tag that points at something that does not build is
worse than no tag.

## Opening the next line — do this in the same sitting as the tag

**Bump `<revision>` to the next `-SNAPSHOT` immediately.** The moment the tag is pushed, `master`
is no longer the released version and must stop claiming to be it.

1. Set `<revision>` in the root `pom.xml` to the next version with `-SNAPSHOT` on it.
2. Correct the places listed in step 4 above.
3. Add a `## [Unreleased]` heading to [CHANGELOG.md](CHANGELOG.md) with a compare link from the tag
   you just pushed to `HEAD`.

A released version number must never be the version an ordinary build produces.
