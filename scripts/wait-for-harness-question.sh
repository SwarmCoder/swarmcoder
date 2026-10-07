#!/usr/bin/env bash
# Blocks until the live harness (EndToEndLoopTest, run with -Dswarmcoder.e2e.askDir=<askDir>) has a
# question that nobody has answered, or the harness has ended.
#
#   scripts/wait-for-harness-question.sh <askDir> <harness-log> [poll-seconds]
#
# Run it as a background shell command and wait for its completion notice; do not poll.
#
# Exit 0: prints the oldest question-<n>.json that has no answer-<n>.json. Answer by writing
#         <askDir>/answer-<n>.json = {"answer":"<token>","text":"<optional>"}, then run this
#         script again for the next question.
# Exit 3: the harness log shows the Maven build finished (BUILD SUCCESS / BUILD FAILURE); prints
#         the last 40 lines of the log. Checked before questions, because a question left behind by
#         a harness that has ended can never be answered.
# Exit 2: wrong usage.
#
# A question that already has an answer is never reported, so the script is safe to start again
# right after answering.
set -u

if [ $# -lt 2 ]; then
    echo "usage: $0 <askDir> <harness-log> [poll-seconds]" >&2
    exit 2
fi
ask_dir=$1
log=$2
poll=${3:-5}

while true; do
    if [ -f "$log" ] && grep -aEq '^\[INFO\] BUILD (SUCCESS|FAILURE)' "$log"; then
        echo "The harness has ended (log: $log). Last lines:"
        tail -n 40 "$log"
        exit 3
    fi
    if [ -d "$ask_dir" ]; then
        # numeric order, so question-10 comes after question-9
        for q in $(ls "$ask_dir" 2>/dev/null | grep -E '^question-[0-9]+\.json$' | sort -t- -k2 -n); do
            n=${q#question-}
            n=${n%.json}
            if [ ! -f "$ask_dir/answer-$n.json" ]; then
                echo "QUESTION $n ($ask_dir/$q), unanswered. Answer in $ask_dir/answer-$n.json"
                cat "$ask_dir/$q"
                exit 0
            fi
        done
    fi
    sleep "$poll"
done
