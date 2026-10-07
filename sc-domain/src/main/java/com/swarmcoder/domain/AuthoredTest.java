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

import com.zeroz4j.api.DataModel;

import java.util.Objects;

/**
 * One acceptance test the test author wrote for a task, and the check it proves.
 *
 * <p>The three facts the run graph shows about a test: its id
 * ({@code swarm.accept.BookTest#storesAllBookFields}), the file it lives in, and - when a check
 * names it - the check's handle and the requirement's own words for it. A test that proves no
 * check the task was asked for still appears, with the last two blank: the author wrote it, so it
 * is counted on the badge, and the panel says that nothing asked for it.
 */
@DataModel
public class AuthoredTest {
    private String testRef;
    /** Repo-relative path of the file that declares it. */
    private String path;
    /** The check this test proves, as its handle (R1:C1), or "" when no check names it. */
    private String provesRef;
    /** That check's wording, or "" when no check names it. */
    private String provesText;

    public AuthoredTest() {}

    public AuthoredTest(String testRef, String path, String provesRef, String provesText) {
        this.testRef = testRef;
        this.path = path;
        this.provesRef = provesRef;
        this.provesText = provesText;
    }

    public String testRef() { return testRef; }
    public String getTestRef() { return testRef; }
    public void setTestRef(String testRef) { this.testRef = testRef; }
    public String path() { return path; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String provesRef() { return provesRef == null ? "" : provesRef; }
    public String getProvesRef() { return provesRef; }
    public void setProvesRef(String provesRef) { this.provesRef = provesRef; }
    public String provesText() { return provesText == null ? "" : provesText; }
    public String getProvesText() { return provesText; }
    public void setProvesText(String provesText) { this.provesText = provesText; }

    /** True when a check the task was asked for names this test. */
    public boolean provesACheck() {
        return !provesRef().isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AuthoredTest that = (AuthoredTest) o;
        return Objects.equals(testRef, that.testRef) && Objects.equals(path, that.path)
            && Objects.equals(provesRef(), that.provesRef())
            && Objects.equals(provesText(), that.provesText());
    }

    @Override
    public int hashCode() {
        return Objects.hash(testRef, path, provesRef(), provesText());
    }
}
