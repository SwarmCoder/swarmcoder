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
package com.swarmcoder.knowledge;

import com.swarmcoder.runtime.AgentRuntime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live run 75, 2026-10-03: the architect's sessions were sent 2,493,741 prompt tokens over 47
 * calls. The expert's and the workers' sessions replace old lookup results by their first lines;
 * a role's session never asked for that.
 */
class ARolesSessionTidiesItsOldLookupResultsTest {

    @Test
    void aRolesSessionAsksForItsOldResultsToBeTidiedAndStillHearsOfUpcomingCalls() {
        java.util.function.BiConsumer<String, String> upcoming = (tool, args) -> { };

        AgentRuntime.SessionOptions options = LookupAgent.sessionOptions(upcoming);

        assertThat(options.tidyAboveTokens()).isEqualTo(LookupAgent.TIDY_ABOVE_TOKENS)
            .isGreaterThan(0);
        assertThat(options.tidyToTokens()).isEqualTo(LookupAgent.TIDY_TO_TOKENS)
            .isLessThan(options.tidyAboveTokens());
        assertThat(options.digestChars()).isEqualTo(LookupAgent.DIGEST_CHARS);
        assertThat(options.upcoming()).isSameAs(upcoming);
    }

    @Test
    void theOrdinaryThresholdIsTwentyThousandTokensUnlessConfigured() {
        assertThat(LookupAgent.TIDY_ABOVE_TOKENS).isEqualTo(
            Integer.getInteger("swarmcoder.roles.tidyAboveTokens", 20_000));
    }

    @Test
    void aRoleKeepsMoreOfWhatItReadThanTheExpertDoes() {
        assertThat(LookupAgent.TIDY_ABOVE_TOKENS)
            .isGreaterThan(ExpertEscalation.TIDY_ABOVE_TOKENS);
        assertThat(LookupAgent.TIDY_TO_TOKENS).isGreaterThan(ExpertEscalation.TIDY_TO_TOKENS);
    }

    @Test
    void theTidyMarkIsAFixedFigureInAnyRoomOfAboutNinetyThousandTokensOrMore() {
        int mark = LookupAgent.TIDY_ABOVE_TOKENS;

        for (int room : new int[] {98_304, 262_144, 1_000_000}) {
            AgentRuntime.SessionOptions options = LookupAgent.sessionOptions(null, room);
            org.assertj.core.api.Assertions.assertThat(options.tidyAboveTokens()).isEqualTo(mark);
        }
    }
}
