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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three sizing layers: a story, then its project, then the settings file — and the fourth
 * answer for when nothing anywhere states a number.
 *
 * <p>Half of these assert the WORDS rather than the number, and that is the point of the class.
 * The failure being prevented is not a wrong worker count; it is a right worker count whose origin
 * nobody can see, which is how {@code splitAcrossFamilies} spent months reading as switched on
 * while doing nothing at all.
 */
class SwarmSizingTest {

    @Test
    void theStoryWinsOverEverything() {
        SwarmSizing sizing = SwarmSizing.resolve(8, "S3", 6, 4);
        assertThat(sizing.workersPerTask()).isEqualTo(8);
        assertThat(sizing.layer()).isEqualTo(SwarmSizing.Layer.STORY);
        assertThat(sizing.label()).contains("story S3");
    }

    @Test
    void theProjectWinsWhenTheStorySaysNothing() {
        SwarmSizing sizing = SwarmSizing.resolve(null, "S3", 6, 4);
        assertThat(sizing.workersPerTask()).isEqualTo(6);
        assertThat(sizing.layer()).isEqualTo(SwarmSizing.Layer.PROJECT);
        assertThat(sizing.label()).contains("this project's own settings");
    }

    @Test
    void theSettingsFileWinsWhenNeitherSaysAnything() {
        SwarmSizing sizing = SwarmSizing.resolve(null, "S3", null, 10);
        assertThat(sizing.workersPerTask()).isEqualTo(10);
        assertThat(sizing.layer()).isEqualTo(SwarmSizing.Layer.GLOBAL);
        assertThat(sizing.label()).contains("the settings file");
    }

    @Test
    void nothingStatedAnywhereIsFourAndSaysSo() {
        SwarmSizing sizing = SwarmSizing.resolve(null, null, null, null);
        assertThat(sizing.workersPerTask()).isEqualTo(4);
        assertThat(sizing.layer()).isEqualTo(SwarmSizing.Layer.BUILT_IN);
        assertThat(SwarmSizing.BUILT_IN_WORKERS_PER_TASK).isEqualTo(4);
    }

    /** Zero and negatives are "unset", not "no workers" — a task with no attempts is not a task. */
    @Test
    void zeroAtAnyLayerMeansInheritRatherThanNoWorkers() {
        assertThat(SwarmSizing.resolve(0, "S1", 6, 4).workersPerTask()).isEqualTo(6);
        assertThat(SwarmSizing.resolve(0, "S1", 0, 4).workersPerTask()).isEqualTo(4);
        assertThat(SwarmSizing.resolve(-3, "S1", 0, 0).workersPerTask()).isEqualTo(4);
    }

    @Test
    void theSentenceIsReadableWithNoOtherContext() {
        assertThat(SwarmSizing.resolve(4, "S7", null, null).sentence())
            .isEqualTo("4 workers on each piece of work, because story S7 asks for 4");
        assertThat(SwarmSizing.resolve(1, "S7", null, null).sentence()).contains("1 worker on");
    }

    /** The reason travels ON the policy, so a finished run still answers "why four?". */
    @Test
    void thePolicyCarriesBothTheCountAndTheReason() {
        SwarmPolicy base = new SwarmPolicy(4, false, 0.2, 0.8, List.of("minimal-diff"),
            "the settings file asks for 4");
        SwarmPolicy resized = base.withWorkers(SwarmSizing.resolve(8, "S3", null, null));

        assertThat(resized.n()).isEqualTo(8);
        assertThat(resized.nSource()).contains("story S3");
        assertThat(resized.personaIds()).isEqualTo(base.personaIds());
        assertThat(resized.tempMin()).isEqualTo(base.tempMin());
        assertThat(base.n()).describedAs("the original is untouched").isEqualTo(4);
    }

    /** A story carries its own number, or null meaning "whatever the project says". */
    @Test
    void aStorySizeIsOptionalAndPartOfTheStory() {
        Story story = new Story();
        assertThat(story.workersPerTask()).isNull();
        story.setWorkersPerTask(6);
        assertThat(story.workersPerTask()).isEqualTo(6);

        Story same = new Story();
        same.setWorkersPerTask(6);
        Story different = new Story();
        different.setWorkersPerTask(4);
        assertThat(story).isEqualTo(same);
        assertThat(story).isNotEqualTo(different);
    }
}
