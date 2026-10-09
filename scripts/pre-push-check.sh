#!/usr/bin/env bash
# Checks what is about to be published before it leaves this machine.
#
#   scripts/pre-push-check.sh [base] [tip]      default: origin/master HEAD
#
# Looks at three things, because all three are published by a push: the files at the tip, the
# messages of the commits between base and tip, and every line those commits added (a line that
# was added and removed again is still in the history).
#
# It refuses machine paths, user folders, private network addresses, keys and tokens, mail
# addresses, a tracked file that .gitignore says must stay local, and a Java source file without
# the licence header. Names that are private to the maintainer cannot be written in this file,
# because the file is public: they are read, one regular expression per line, from
# ~/.swarmcoder/private-terms.txt (or the file SWARMCODER_PRIVATE_TERMS names).
#
# Exit 0 when nothing was found, 1 when something was, 2 when it could not run.
# scripts/hooks/pre-push runs it on every push once `git config core.hooksPath scripts/hooks`
# is set in the checkout.

set -u
cd "$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "pre-push-check: not in a git checkout" >&2; exit 2; }

base="${1:-origin/master}"
tip="${2:-HEAD}"
git rev-parse -q --verify "$base^{commit}" >/dev/null || { echo "pre-push-check: no commit '$base'" >&2; exit 2; }
git rev-parse -q --verify "$tip^{commit}" >/dev/null || { echo "pre-push-check: no commit '$tip'" >&2; exit 2; }

# What must never be published, whoever the maintainer is.
forbidden='[a-z]:[/\\]+proj[/\\]'
forbidden+='|[/\\]Users[/\\]+[a-z0-9._-]+[/\\]'
forbidden+='|/home/[a-z0-9._-]+/'
forbidden+='|\b192\.168\.[0-9]+\.[0-9]+'
forbidden+='|\b10\.[0-9]+\.[0-9]+\.[0-9]+'
forbidden+='|\b172\.(1[6-9]|2[0-9]|3[01])\.[0-9]+\.[0-9]+'
forbidden+='|\bsk-[A-Za-z0-9_-]{16,}'
forbidden+='|\bgh[pousr]_[A-Za-z0-9]{20,}'
forbidden+='|\bAKIA[0-9A-Z]{16}'
forbidden+='|BEGIN [A-Z ]*PRIVATE KEY'
forbidden+='|[A-Za-z0-9._-]+@[A-Za-z0-9-]+\.(com|net|org|io|de|dev|ai)\b'

# What looks like the above and is not: the placeholder address the documents use, the user
# folder written as a placeholder, and the two mail addresses that are meant to be public.
allowed='192\.168\.0\.10\b|[/\\]Users[/\\]+(you|me|name|user|username|dev)[/\\]|/home/(you|me|name|user|username|dev|op)/'
allowed+='|noreply@anthropic\.com|@example\.(com|org|net)|git@github\.com'
# The tests of the product's own secret scanner have to contain things shaped like secrets.
allowed+='|AKIAIOSFODNN7EXAMPLE|SecretScannerTest\.java'

terms_file="${SWARMCODER_PRIVATE_TERMS:-$HOME/.swarmcoder/private-terms.txt}"
if [ -f "$terms_file" ]; then
    while IFS= read -r term; do
        term="${term%$'\r'}"
        case "$term" in ''|'#'*) continue ;; esac
        forbidden+="|$term"
    done < "$terms_file"
else
    echo "pre-push-check: no private terms file at $terms_file; only the general patterns are checked"
fi

findings="$(mktemp)"
trap 'rm -f "$findings"' EXIT
report() {   # title, then the offending lines on stdin
    local lines
    lines="$(cut -c1-220 | head -40)"
    [ -z "$lines" ] && return
    printf '\n%s\n%s\n' "$1" "$lines" >> "$findings"
}

git grep -n -I -i -E "$forbidden" "$tip" -- . ':!scripts/pre-push-check.sh' \
    | grep -v -i -E "$allowed" \
    | report "In the files at $tip:"

git log --format='%h %s%n%b' "$base..$tip" \
    | grep -n -i -E "$forbidden" | grep -v -i -E "$allowed" \
    | report "In the commit messages of $base..$tip:"

git log -p --no-merges --format='commit %h' "$base..$tip" -- . ':!scripts/pre-push-check.sh' \
    | awk '/^commit /{c=$2; next} /^\+\+\+ /{f=substr($0,7); next} /^\+/{print c" "f": "substr($0,2)}' \
    | grep -i -E "$forbidden" | grep -v -i -E "$allowed" \
    | report "In lines added by the commits of $base..$tip (still in the history if removed later):"

git ls-files -ci --exclude-standard \
    | report "Tracked although .gitignore says it stays local:"

git ls-files 'sc-*.java' | grep -E '^sc-[^/]+/src/(main|test)/java/' \
    | xargs -r grep -L "Licensed under the Apache License" \
    | report "Java source files without the Apache 2.0 header:"

git diff --diff-filter=A --name-only "$base" "$tip" \
    | while IFS= read -r path; do
          size="$(git cat-file -s "$tip:$path" 2>/dev/null || echo 0)"
          [ "$size" -gt 1000000 ] && echo "$path ($size bytes)"
      done \
    | report "Added files larger than 1 MB (a picture or a dump can show what text checks cannot):"

if [ -s "$findings" ]; then
    cat "$findings"
    printf '\npre-push-check: REFUSED. Nothing above may be published; take it out of the files and, when it is in a commit, out of the history.\n'
    exit 1
fi
echo "pre-push-check: clean ($(git rev-list --count "$base..$tip") commits, $base..$tip)"
