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
package com.swarmcoder.git;

import com.swarmcoder.domain.Run;

/**
 * Decides, once, the commit a run builds on.
 *
 * <p>One implementation because two callers start runs — the Console's intake and the end-to-end
 * harness — and until this class existed only the Console pinned. The harness cut every worktree
 * from a live {@code HEAD}, which happened to carry the acceptance tests, so it walked the whole
 * chain green while every Console-started run executed zero acceptance tests for a week. A
 * harness that starts a run differently from the product measures something other than the
 * product.
 */
public final class BasePin {

    private BasePin() {}

    /**
     * Pins {@code run} to the tip of the repository's current branch. Does nothing when git is
     * disabled; the run then reads as "HEAD" wherever a start point is needed.
     *
     * @return the sentence for the run's log, or null when nothing was pinned
     */
    public static String pin(Run run, GitService git) {
        if (git == null || !git.isEnabled()) {
            return null;
        }
        String branch = git.currentBranch();
        String base = git.resolveCommit(branch == null ? "HEAD" : branch);
        run.setBaseRef(branch);
        run.setBaseCommit(base);
        return "Run " + run.id() + " builds on " + (branch == null ? "HEAD" : branch) + " at " + base;
    }
}
