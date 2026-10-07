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

/**
 * What a {@link ChangeEvent} records happening. {@link #CREATED}: the entity came into existence.
 * {@link #UPDATED}: a field's value changed. {@link #STATE_CHANGED}: its lifecycle state moved.
 * {@link #LINKED}: a relationship to another entity was added. {@link #UNLINKED}: such a
 * relationship was removed. {@link #PROMOTED}: it advanced in standing (e.g. a proposed criterion
 * accepted, a draft story made ready). {@link #TOMBSTONED}: it was retired from the live plan but
 * kept for history. {@link #RESTORED}: a tombstoned or past state was brought back.
 */
public enum ChangeKind {
    CREATED, UPDATED, STATE_CHANGED, LINKED, UNLINKED, PROMOTED, TOMBSTONED, RESTORED;

    /**
     * What this entry records, in words. The change journal in a story's dialog is read by the
     * operator, and it used to print these constants — STATE_CHANGED, TOMBSTONED — beside
     * each line (UX v3 §5 rule 3).
     */
    public String label() {
        return switch (this) {
            case CREATED -> "created";
            case UPDATED -> "edited";
            case STATE_CHANGED -> "moved on";
            case LINKED -> "linked";
            case UNLINKED -> "unlinked";
            case PROMOTED -> "agreed";
            case TOMBSTONED -> "retired";
            case RESTORED -> "brought back";
        };
    }
}
