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

import java.util.Objects;

public class LibraryDoc {
    private String coordinate;
    private String version;
    private String docsExcerpt;

    public LibraryDoc() {}

    public LibraryDoc(String coordinate, String version, String docsExcerpt) {
        this.coordinate = coordinate;
        this.version = version;
        this.docsExcerpt = docsExcerpt;
    }

    public String coordinate() { return coordinate; }
    public String getCoordinate() { return coordinate; }
    public void setCoordinate(String coordinate) { this.coordinate = coordinate; }
    public String version() { return version; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String docsExcerpt() { return docsExcerpt; }
    public String getDocsExcerpt() { return docsExcerpt; }
    public void setDocsExcerpt(String docsExcerpt) { this.docsExcerpt = docsExcerpt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LibraryDoc that = (LibraryDoc) o;
        return Objects.equals(this.coordinate, that.coordinate) && Objects.equals(this.version, that.version) && Objects.equals(this.docsExcerpt, that.docsExcerpt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(coordinate, version, docsExcerpt);
    }
}

