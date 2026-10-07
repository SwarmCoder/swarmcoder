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
package com.swarmcoder.domain;

import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;
import java.util.Objects;

/**
 * What the project's build would and would not compile out of what a candidate wrote.
 *
 * <p>Carried on the {@link VerificationReport} beside the compile flag, because it is the compile
 * flag it corrects. {@code compiles=true} only ever meant "the commands in the verification
 * contract exited 0"; it never meant "your code was among what they compiled". Where the two
 * disagree, this record is the one that says so, and {@code Verdicts} fails the candidate on it.
 *
 * <p>A report written before this field existed carries null, which reads as UNDETERMINED —
 * never as "orphaned".
 */
@JsonTypeName("BuildReachability")
public class BuildReachability {

    private BuildReachabilityStatus status;
    /** The source, test and resource roots the build was found to compile or package. */
    private List<String> compiledRoots;
    /** Changed files that sit outside all of them. These are what fail the candidate. */
    private List<String> orphanFiles;
    /**
     * Changed files outside every root that are NOT failed — documentation, scripts, build files,
     * and assets inside a module the build does own. Recorded so the operator can see them.
     */
    private List<String> notedFiles;
    /** Plain English: what was read, what was concluded, and what could not be established. */
    private String explanation;

    public BuildReachability() {}

    public BuildReachability(BuildReachabilityStatus status, List<String> compiledRoots,
                             List<String> orphanFiles, List<String> notedFiles, String explanation) {
        this.status = status;
        this.compiledRoots = compiledRoots;
        this.orphanFiles = orphanFiles;
        this.notedFiles = notedFiles;
        this.explanation = explanation;
    }

    public BuildReachabilityStatus status() { return status; }
    public BuildReachabilityStatus getStatus() { return status; }
    public void setStatus(BuildReachabilityStatus status) { this.status = status; }
    public List<String> compiledRoots() { return compiledRoots; }
    public List<String> getCompiledRoots() { return compiledRoots; }
    public void setCompiledRoots(List<String> compiledRoots) { this.compiledRoots = compiledRoots; }
    public List<String> orphanFiles() { return orphanFiles; }
    public List<String> getOrphanFiles() { return orphanFiles; }
    public void setOrphanFiles(List<String> orphanFiles) { this.orphanFiles = orphanFiles; }
    public List<String> notedFiles() { return notedFiles; }
    public List<String> getNotedFiles() { return notedFiles; }
    public void setNotedFiles(List<String> notedFiles) { this.notedFiles = notedFiles; }
    public String explanation() { return explanation; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }

    public boolean orphaned() {
        return status == BuildReachabilityStatus.ORPHANED;
    }

    /**
     * The message an operator can act on without opening anything else: which files are stranded,
     * where the build actually looks, and what to do about it.
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("this candidate wrote ")
          .append(orphanFiles == null ? 0 : orphanFiles.size())
          .append(orphanFiles != null && orphanFiles.size() == 1 ? " file" : " files")
          .append(" where the project's build will never compile or package ")
          .append(orphanFiles != null && orphanFiles.size() == 1 ? "it" : "them")
          .append(':');
        if (orphanFiles != null) {
            for (String file : orphanFiles) {
                sb.append("\n  - ").append(file);
            }
        }
        if (compiledRoots != null && !compiledRoots.isEmpty()) {
            sb.append("\n\nThe build compiles or packages these directories, and nothing else:");
            for (String root : compiledRoots) {
                sb.append("\n  - ").append(root);
            }
        }
        if (explanation != null && !explanation.isBlank()) {
            sb.append("\n\n").append(explanation);
        }
        sb.append("\n\nThe compile stage passed, which is exactly the problem: it compiled the "
            + "modules that were already there and never saw these files. Code in a directory no "
            + "module owns is not in the application, so the work is not done. Put it inside one "
            + "of the directories listed above — the task's write set is pointing at a source root "
            + "this repository does not have.");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BuildReachability that = (BuildReachability) o;
        return this.status == that.status
            && Objects.equals(this.compiledRoots, that.compiledRoots)
            && Objects.equals(this.orphanFiles, that.orphanFiles)
            && Objects.equals(this.notedFiles, that.notedFiles)
            && Objects.equals(this.explanation, that.explanation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, compiledRoots, orphanFiles, notedFiles, explanation);
    }
}
