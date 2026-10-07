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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 75, 2026-10-03: a story whose one check was "the logbook offers no ... contact
 * transcript/debrief" delivered the deletion of three fields, with their getters and setters,
 * from the shared data type and from the commands and the screen that used them. See
 * {@link RemovedExistingApi}.
 */
class AStoryDoesNotRemoveExistingPublicCodeUnlessACheckSaysSoTest {

    private static final String MODEL = "shared/src/main/java/org/example/log/Entry.java";
    private static final String OTHER = "shared/src/main/java/org/example/log/EntryNote.java";
    private static final String TEST = "server/src/test/java/org/example/log/EntryTest.java";

    private static final String BEFORE = """
        package org.example.log;

        public class Entry {
            private String call;
            private String note;

            public Entry() {
            }

            public String getCall() {
                return call;
            }

            public String getNote() {
                return note;
            }

            public void setNote(String note) {
                this.note = note;
            }

            String internal() {
                return call;
            }
        }
        """;

    private static final String WITHOUT_NOTE = """
        package org.example.log;

        public class Entry {
            private String call;

            public Entry() {
            }

            public String getCall(boolean upperCase) {
                return call;
            }
        }
        """;

    private static final List<String> ABSENCE =
        List.of("The log offers no import/export, no uploads, and no notes on an entry.");

    private static String diffOf(String... paths) {
        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            sb.append("diff --git a/").append(path).append(" b/").append(path).append('\n')
                .append("--- a/").append(path).append("\n+++ b/").append(path).append("\n-x\n+y\n");
        }
        return sb.toString();
    }

    @Test
    void removedPublicMembersOfAnExistingTypeAreNamedAndAChangedSignatureIsNot() {
        List<String> removed = RemovedExistingApi.removed(diffOf(MODEL),
            Map.of(MODEL, BEFORE)::get, Map.of(MODEL, WITHOUT_NOTE)::get);

        assertThat(removed).as("getCall kept its name; internal() was never public")
            .containsExactly("Entry.getNote", "Entry.setNote");
        assertThat(RemovedExistingApi.objection(removed, ABSENCE))
            .contains("Entry.getNote, Entry.setNote").contains("existed before this story started")
            .contains("met by not offering it").contains("Put them back")
            .contains("no notes on an entry");
    }

    @Test
    void anAbsenceCheckDoesNotAskForARemovalAndACheckThatSaysRemoveDoes() {
        assertThat(RemovedExistingApi.asksForRemoval(ABSENCE)).isFalse();
        assertThat(RemovedExistingApi.asksForRemoval(
            List.of("The note field is removed from an entry."))).isTrue();
        assertThat(RemovedExistingApi.asksForRemoval(
            List.of("An entry no longer carries a note."))).isTrue();
        assertThat(RemovedExistingApi.asksForRemoval(List.of())).isFalse();
    }

    @Test
    void codeThisRunAddedATestFileAndAnUnchangedSurfaceAreNotRemovals() {
        // Not there at the run's start: an earlier wave of this run wrote it.
        assertThat(RemovedExistingApi.removed(diffOf(MODEL), path -> null,
            Map.of(MODEL, WITHOUT_NOTE)::get)).isEmpty();
        // A test tree is not read.
        assertThat(RemovedExistingApi.removed(diffOf(TEST), Map.of(TEST, BEFORE)::get,
            Map.of(TEST, WITHOUT_NOTE)::get)).isEmpty();
        // Nothing public went.
        assertThat(RemovedExistingApi.removed(diffOf(MODEL), Map.of(MODEL, BEFORE)::get,
            Map.of(MODEL, BEFORE + "// a comment\n")::get)).isEmpty();
        assertThat(RemovedExistingApi.objection(List.of(), ABSENCE)).isNull();
    }

    @Test
    void aDeletedPublicTypeIsARemovalUnlessTheSameChangeDeclaresItElsewhere() {
        String deletion = "diff --git a/" + MODEL + " b/" + MODEL + "\n--- a/" + MODEL
            + "\n+++ /dev/null\n-x\n";
        assertThat(RemovedExistingApi.removed(deletion, Map.of(MODEL, BEFORE)::get, path -> null))
            .containsExactly("Entry");

        String moved = deletion + "diff --git a/" + OTHER + " b/" + OTHER + "\n--- /dev/null\n+++ b/"
            + OTHER + "\n+y\n";
        assertThat(RemovedExistingApi.removed(moved, Map.of(MODEL, BEFORE)::get,
            Map.of(OTHER, BEFORE)::get)).isEmpty();
    }
}
