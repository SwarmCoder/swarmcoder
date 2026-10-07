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

import java.util.List;
import java.util.Objects;

public class PageCheck {
    private String url;
    private boolean loaded;
    private List<String> consoleErrors;
    private List<AssertionResult> assertions;
    private String screenshotRef;

    public PageCheck() {}

    public PageCheck(String url, boolean loaded, List<String> consoleErrors, List<AssertionResult> assertions, String screenshotRef) {
        this.url = url;
        this.loaded = loaded;
        this.consoleErrors = consoleErrors;
        this.assertions = assertions;
        this.screenshotRef = screenshotRef;
    }

    public String url() { return url; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public boolean loaded() { return loaded; }
    public boolean getLoaded() { return loaded; }
    public void setLoaded(boolean loaded) { this.loaded = loaded; }
    public List<String> consoleErrors() { return consoleErrors; }
    public List<String> getConsoleErrors() { return consoleErrors; }
    public void setConsoleErrors(List<String> consoleErrors) { this.consoleErrors = consoleErrors; }
    public List<AssertionResult> assertions() { return assertions; }
    public List<AssertionResult> getAssertions() { return assertions; }
    public void setAssertions(List<AssertionResult> assertions) { this.assertions = assertions; }
    public String screenshotRef() { return screenshotRef; }
    public String getScreenshotRef() { return screenshotRef; }
    public void setScreenshotRef(String screenshotRef) { this.screenshotRef = screenshotRef; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PageCheck that = (PageCheck) o;
        return Objects.equals(this.url, that.url) && this.loaded == that.loaded && Objects.equals(this.consoleErrors, that.consoleErrors) && Objects.equals(this.assertions, that.assertions) && Objects.equals(this.screenshotRef, that.screenshotRef);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, loaded, consoleErrors, assertions, screenshotRef);
    }
}

