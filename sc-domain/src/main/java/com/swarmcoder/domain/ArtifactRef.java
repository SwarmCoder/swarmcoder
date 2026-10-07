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
import java.util.UUID;

public class ArtifactRef {
    private UUID id;
    private long revision;

    public ArtifactRef() {}

    public ArtifactRef(UUID id, long revision) {
        this.id = id;
        this.revision = revision;
    }

    public UUID id() { return id; }
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public long revision() { return revision; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ArtifactRef that = (ArtifactRef) o;
        return Objects.equals(this.id, that.id) && this.revision == that.revision;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, revision);
    }
}

