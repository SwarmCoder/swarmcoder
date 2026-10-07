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

public class ClusterId {
    private String behavioralHash;
    private int clusterSize;

    public ClusterId() {}

    public ClusterId(String behavioralHash, int clusterSize) {
        this.behavioralHash = behavioralHash;
        this.clusterSize = clusterSize;
    }

    public String behavioralHash() { return behavioralHash; }
    public String getBehavioralHash() { return behavioralHash; }
    public void setBehavioralHash(String behavioralHash) { this.behavioralHash = behavioralHash; }
    public int clusterSize() { return clusterSize; }
    public int getClusterSize() { return clusterSize; }
    public void setClusterSize(int clusterSize) { this.clusterSize = clusterSize; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClusterId that = (ClusterId) o;
        return Objects.equals(this.behavioralHash, that.behavioralHash) && this.clusterSize == that.clusterSize;
    }

    @Override
    public int hashCode() {
        return Objects.hash(behavioralHash, clusterSize);
    }
}

