/*
 * Copyright 2026 Franz Schöning
 * Project: https://github.com/SwarmCoder/swarmcoder
 * Author: Franz Schöning - Principal Enterprise Architect (https://www.franzschoning.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.swarmcoder.swarm;

import com.swarmcoder.domain.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the operator is told when a winning branch will not merge — the one wording, used by both
 * places a merge now happens.
 *
 * <p>The message used to be the branch name and git's own output, which says which FILE conflicted
 * and nothing about why two tasks were both in it. Since workers are no longer killed for writing
 * outside their slice, that is now the likely cause and it is knowable: every winning diff is
 * audited on the way in, so the path an earlier task also reached can be named outright, together
 * with the task that reached it.
 *
 * <p><b>This is the cost of allowing out-of-set writes, stated plainly.</b> Disjoint write sets
 * used to make these merges conflict-free by construction, and allowing out-of-set writes gives
 * that up. The trade is deliberate: the old guarantee was bought by killing workers mid-flight, and
 * a parked run is a far better failure than a destroyed candidate — the branches all still exist,
 * the diffs are all still readable, and this message says exactly which two pieces of work
 * overlapped and where.
 *
 * <p>Extracted from {@code FinalIntegrator} when winners started being merged BETWEEN waves as well
 * as at the end. Two copies of this sentence would be two different accounts of the same event.
 */
public final class MergeConflictBrief {

    private MergeConflictBrief() {}

    /**
     * @param branch         the winning branch that would not merge
     * @param task           the task that branch belongs to
     * @param outOfWriteSet  paths that winner changed which its task does not own
     * @param claimedBy      path -> the title of the task whose winner reached it first, for the
     *                       whole run so far
     * @param gitMessage     git's own output
     */
    public static String of(String branch, Task task, List<String> outOfWriteSet,
                            Map<String, String> claimedBy, String gitMessage) {
        StringBuilder brief = new StringBuilder("Merge of ").append(branch)
            .append(" (task '").append(task.title()).append("') conflicted:\n").append(gitMessage);
        List<String> shared = new ArrayList<>();
        for (String path : outOfWriteSet == null ? List.<String>of() : outOfWriteSet) {
            String earlier = claimedBy == null ? null : claimedBy.get(path);
            if (earlier != null && !earlier.equals(task.title())) {
                shared.add(path + " (also changed by task '" + earlier + "')");
            }
        }
        if (!shared.isEmpty()) {
            brief.append("\n\nTwo tasks changed the same file, and neither one owns it:");
            shared.forEach(line -> brief.append("\n  ").append(line));
            brief.append("\n\nBoth pieces of work are on their branches and nothing is lost. "
                + "Merge them by hand, or re-plan so one task owns that file.");
        } else if (outOfWriteSet != null && !outOfWriteSet.isEmpty()) {
            brief.append("\n\nThis candidate changed ").append(outOfWriteSet.size())
                .append(" file(s) outside its own paths, which is where a conflict is most "
                    + "likely to have come from: ")
                .append(String.join(", ", outOfWriteSet));
        }
        return brief.toString();
    }
}
