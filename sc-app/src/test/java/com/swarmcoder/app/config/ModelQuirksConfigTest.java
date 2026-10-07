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
package com.swarmcoder.app.config;

import com.swarmcoder.inference.ModelQuirks;
import com.swarmcoder.inference.ModelShapes;
import com.swarmcoder.inference.ServerCapabilities;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a model's settings are decided: the named shape first, then whatever the operator stated,
 * then the role's own thinking flag.
 *
 * <p>The load-bearing case is {@link #aConfigThatSaysNothingKeepsShipping}. Everything here used to
 * be a constant, so an existing config mentions none of it; if the fallback chain moved even one
 * value, an upgrade would silently change how a working model is spoken to.
 */
class ModelQuirksConfigTest {

    @Test
    void aConfigThatSaysNothingKeepsShipping() {
        ModelQuirks q = new AgentModelConfig("OpenAI", "http://box:8000", null, "m", null)
            .resolvedQuirks();

        assertThat(q.maxOutputTokens()).isEqualTo(32768);
        // The one deliberate exception (2026-09-04): thinking now defaults ON. The old default
        // (off) was itself the thing the plain-loop experiment disproved, so "keeps shipping
        // unchanged" does not apply to this one field — see ModelQuirks's class note.
        assertThat(q.thinking()).isTrue();
        assertThat(q.textualToolHistory()).isTrue();
        assertThat(q.jsonResponseFormat()).isTrue();
        assertThat(q.http2()).isFalse();
        assertThat(q.servedContextTokens()).isEqualTo(65536);
        assertThat(q.workingContextTokens()).isEqualTo(32768);
    }

    @Test
    void theGenericShapeSendsNoReasoningSwitchAtAll() {
        // Nothing is known about an uncharacterised model's chat template, and sending it an
        // argument it does not recognise can fail the request outright.
        ModelQuirks q = ModelShapes.get(ModelShapes.GENERIC);

        assertThat(q.sendsThinkingKwarg()).isFalse();
        assertThat(q.appendsNoThinkDirective()).isFalse();
        assertThat(q.verified()).isFalse();
    }

    @Test
    void theQwenShapesCarryTheQwenConventions() {
        ModelQuirks measured = ModelShapes.get("qwen36-27b");
        ModelQuirks replacement = ModelShapes.get("qwen38-flash-next-125b");

        assertThat(measured.thinkingKwarg()).isEqualTo("enable_thinking");
        assertThat(measured.noThinkDirective()).isEqualTo("/no_think");
        assertThat(replacement.thinkingKwarg()).isEqualTo("enable_thinking");
        assertThat(replacement.noThinkDirective()).isEqualTo("/no_think");
    }

    @Test
    void onlyTheCurrentShapeDefaultsToNativeToolHistory() {
        // Koog 1.2.0 (2026-09-04) fixed the double-encoding defect that forced textual tool
        // history on every model; a live probe against this exact shape's endpoint proved native
        // history round-trips clean, so this is the one shape that defaults to it. The others keep
        // the safe direction until they, too, are measured.
        assertThat(ModelShapes.get("qwen38-flash-next-125b").textualToolHistory()).isFalse();
        assertThat(ModelShapes.get("qwen36-27b").textualToolHistory()).isTrue();
        assertThat(ModelShapes.get(ModelShapes.GENERIC).textualToolHistory()).isTrue();
    }

    @Test
    void onlyTheModelSomebodyMeasuredIsMarkedMeasured() {
        // The whole point of the flag: the new model inherits the family's chat-template
        // conventions but NOT the old model's measured numbers, and startup says so every boot.
        assertThat(ModelShapes.get("qwen36-27b").verified()).isTrue();
        assertThat(ModelShapes.get("qwen38-flash-next-125b").verified()).isFalse();
        assertThat(ModelShapes.get("vision").verified()).isFalse();
    }

    @Test
    void anUnknownShapeFallsBackLoudlyRatherThanFailingToStart() {
        // Config outlives a catalogue. A run that refuses to start because a shape was renamed is
        // worse than one that starts conservatively and labels itself.
        ModelQuirks q = ModelQuirksConfig.resolve("a-shape-that-was-deleted", null, null);

        assertThat(q.label()).contains("UNKNOWN shape");
        assertThat(q.sendsThinkingKwarg()).isFalse();
    }

    @Test
    void statedSettingsOverrideTheShape() {
        ModelQuirksConfig overrides = new ModelQuirksConfig(
            8192, null, null, false, false, "2", 131072, 65536, 2048, 4, true);
        ModelQuirks q = ModelQuirksConfig.resolve("qwen36-27b", overrides, null);

        assertThat(q.maxOutputTokens()).isEqualTo(8192);
        assertThat(q.textualToolHistory()).isFalse();
        assertThat(q.jsonResponseFormat()).isFalse();
        assertThat(q.http2()).isTrue();
        assertThat(q.servedContextTokens()).isEqualTo(131072);
        assertThat(q.workingContextTokens()).isEqualTo(65536);
        assertThat(q.kvBytesPerToken()).isEqualTo(2048);
        assertThat(q.maxConcurrentSequences()).isEqualTo(4);
        assertThat(q.verified()).isTrue();
        // Unstated fields still come from the shape.
        assertThat(q.thinkingKwarg()).isEqualTo("enable_thinking");
    }

    @Test
    void theWordNoneMeansThisModelHasNoSuchMechanism() {
        // Different from leaving it out: blank inherits the Qwen argument, "none" removes it.
        ModelQuirksConfig overrides = new ModelQuirksConfig(
            null, "none", "none", null, null, null, null, null, null, null, null);
        ModelQuirks q = ModelQuirksConfig.resolve("qwen36-27b", overrides, null);

        assertThat(q.thinkingKwarg()).isNull();
        assertThat(q.noThinkDirective()).isNull();
        assertThat(q.sendsThinkingKwarg()).isFalse();
        assertThat(q.appendsNoThinkDirective()).isFalse();
    }

    @Test
    void theRolesOwnThinkingFlagWinsOverTheShape() {
        ModelQuirks on = ModelQuirksConfig.resolve("qwen36-27b", null, true);
        ModelQuirks off = ModelQuirksConfig.resolve("qwen36-27b", null, false);

        assertThat(on.thinking()).isTrue();
        assertThat(on.sendsThinkingKwarg()).isFalse();   // reasoning on: send nothing, model default
        assertThat(off.thinking()).isFalse();
        assertThat(off.sendsThinkingKwarg()).isTrue();
    }

    @Test
    void twoRolesOnOneBoxCanDisagreeAboutEverything() {
        // The thing that was impossible while these were JVM-wide system properties.
        AgentModelConfig worker = new AgentModelConfig("OpenAI", "http://box:8000", null, "m", false,
            "qwen38-flash-next-125b", null);
        AgentModelConfig vision = new AgentModelConfig("OpenAI", "http://box:8000", null, "v", null,
            "vision", new ModelQuirksConfig(2048, null, null, null, null, null,
                null, null, null, null, null));

        assertThat(worker.resolvedQuirks().sendsThinkingKwarg()).isTrue();
        assertThat(worker.resolvedQuirks().maxOutputTokens()).isEqualTo(32768);
        assertThat(vision.resolvedQuirks().sendsThinkingKwarg()).isFalse();
        assertThat(vision.resolvedQuirks().maxOutputTokens()).isEqualTo(2048);
    }

    // --- the server's own account of itself, in the middle of the chain -------------------------

    /** What the 2026-08 box reports: 262144 per request, 8 at once, a 462103-token cache. */
    private static ServerCapabilities theBox() {
        return new ServerCapabilities("http://box:8002", 262144, 8, 462103, "measured");
    }

    @Test
    void whatTheServerReportsBeatsAShapeNobodyMeasured() {
        // The generic shape carries 65536 / 32768 / 16, none of it measured against anything. A
        // live server saying otherwise is better evidence than a constant, and wins.
        ModelQuirks q = new AgentModelConfig("OpenAI", "http://box:8002", null, "m", null,
            "generic-openai", null).resolvedQuirks(theBox());

        assertThat(q.servedContextTokens()).isEqualTo(262144);
        assertThat(q.maxConcurrentSequences()).isEqualTo(8);
        assertThat(q.workingContextTokens()).isEqualTo(51200);
    }

    @Test
    void anOperatorWhoWroteTheNumberDownKeepsIt() {
        // Writing servedContextTokens into a config is a deliberate act — usually to hold a model
        // below what the box would allow. Discovery fills gaps; it never overrules a decision.
        ModelQuirksConfig stated = new ModelQuirksConfig(null, null, null, null, null, null,
            /*servedContextTokens*/ 40000, /*workingContextTokens*/ 20000, null,
            /*maxConcurrentSequences*/ 4, null);

        ModelQuirks q = new AgentModelConfig("OpenAI", "http://box:8002", null, "m", null,
            "generic-openai", stated).resolvedQuirks(theBox());

        assertThat(q.servedContextTokens()).isEqualTo(40000);
        assertThat(q.workingContextTokens()).isEqualTo(20000);
        assertThat(q.maxConcurrentSequences()).isEqualTo(4);
    }

    @Test
    void statingOneFigureLeavesTheServerToSupplyTheRest() {
        ModelQuirksConfig stated = new ModelQuirksConfig(null, null, null, null, null, null,
            null, null, null, /*maxConcurrentSequences*/ 2, null);

        ModelQuirks q = new AgentModelConfig("OpenAI", "http://box:8002", null, "m", null,
            "generic-openai", stated).resolvedQuirks(theBox());

        assertThat(q.maxConcurrentSequences()).isEqualTo(2);
        assertThat(q.servedContextTokens())
            .describedAs("nothing was stated about the context, so the server's figure stands")
            .isEqualTo(262144);
    }

    @Test
    void aServerThatSaidNothingLeavesEverythingExactlyAsItWas() {
        // The case that runs whenever a model box is switched off, which is most restarts.
        AgentModelConfig role = new AgentModelConfig("OpenAI", "http://box:8002", null, "m", null,
            "generic-openai", null);

        assertThat(role.resolvedQuirks(ServerCapabilities.NOTHING))
            .isEqualTo(role.resolvedQuirks());
    }

    @Test
    void theHostedDeepSeekApiWithNoShapeGetsTheHostedShapeNotTheSparksLimits() {
        AgentModelConfig role = new AgentModelConfig("OpenAI", "https://api.deepseek.com", null,
            "deepseek-v4-flash", null);

        ModelQuirks q = role.resolvedQuirks();

        assertThat(q.workingContextTokens()).isEqualTo(983040);
        assertThat(q.servedContextTokens()).isEqualTo(1048576);
        assertThat(q.jsonResponseFormat()).isTrue();
        assertThat(q.maxConcurrentSequences()).isEqualTo(16);
        assertThat(q.loadedTokensPerSecond())
            .isEqualTo(ModelShapes.get(ModelShapes.GENERIC).loadedTokensPerSecond());
    }

    @Test
    void aNamedShapeBeatsTheOneChosenByHostAndAnOtherHostIsUntouched() {
        AgentModelConfig named = new AgentModelConfig("OpenAI", "https://api.deepseek.com", null,
            "deepseek-v4-flash", null, "generic-openai", null);
        AgentModelConfig spark = new AgentModelConfig("OpenAI", "http://192.168.0.10:8000/v1",
            null, "deepseek-v4-flash", null);

        assertThat(named.resolvedQuirks().workingContextTokens()).isEqualTo(32768);
        assertThat(spark.resolvedQuirks().workingContextTokens()).isEqualTo(32768);
    }
}
