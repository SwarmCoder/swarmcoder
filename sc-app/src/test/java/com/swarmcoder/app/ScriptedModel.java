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
package com.swarmcoder.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.swarmcoder.swarm.FakeVllm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Everything the models say during the full-product journey, and nothing else.
 *
 * <p>This is the whole point of the dress rehearsal: the ONLY thing simulated is the model's words.
 * Requests arrive over real HTTP at a real OpenAI-compatible endpoint ({@link FakeVllm}), from the
 * real clients, and every reply is routed by what the caller actually said — which is how eight
 * different agents sharing one endpoint stay separable without any ordering state, and therefore
 * without caring that several of them run concurrently.
 *
 * <p><b>Streaming matters.</b> The role clients stream (server-sent events) and the worker loop does
 * not, and FakeVllm's SSE path can only carry text. So every role must be answered with
 * {@link FakeVllm.Reply#text}, and only a worker turn may be answered with a tool call.
 */
final class ScriptedModel implements Function<String, FakeVllm.Reply> {

    /** The class the acceptance tests are written into — and what the criteria name. */
    static final String ACCEPT_CLASS = "swarm.accept.MultiplyAcceptTest";
    static final String CRITERION_ONE_TEST = ACCEPT_CLASS + "#multipliesTwoPositiveNumbers";
    static final String CRITERION_TWO_TEST = ACCEPT_CLASS + "#multiplyingByZeroGivesZero";
    /**
     * What the requirements wizard SUGGESTS for the second check — deliberately not what the test
     * author will actually write. The operator corrects it in the editor, which is the whole point
     * of a proposal: the wizard offers a name, a person settles it, and the run then proves it.
     */
    static final String CRITERION_TWO_PROPOSED = ACCEPT_CLASS + "#zeroGivesZero";

    /** Every prompt the endpoint was sent, for asserting on what the product asked. */
    private final List<String> seen = new ArrayList<>();

    List<String> seen() {
        synchronized (seen) {
            return List.copyOf(seen);
        }
    }

    /** True when some prompt contained the marker — "was this role actually invoked?" */
    boolean sawPromptContaining(String marker) {
        return seen().stream().anyMatch(p -> p.contains(marker));
    }

    @Override
    public FakeVllm.Reply apply(String conversation) {
        synchronized (seen) {
            seen.add(conversation);
        }

        // --- the worker, first: its own turns must never be mistaken for a role's --------------
        if (conversation.contains("software engineering worker agent")) {
            if (conversation.contains("wrote src/main/java/com/example/calc/Calculator.java")) {
                return FakeVllm.Reply.toolCall("report_done",
                    "{\"summary\": \"added multiply(int,int) to Calculator\"}");
            }
            return FakeVllm.Reply.toolCall("write_file", writeCalculatorArgs());
        }

        // --- the two Console wizards -------------------------------------------------------------
        if (conversation.contains("requirements analyst reading a client's documents")) {
            // One genuine ambiguity, so the answer round is exercised rather than skipped.
            return FakeVllm.Reply.text("""
                {"questions":[{"subject":"Number range",\
                "text":"Which numbers must multiplication accept?",\
                "sourceQuote":"the calculator must multiply two whole numbers",\
                "sourceDocument":"calculator-requirements.md",\
                "background":"The document says whole numbers without saying whether negatives \
                and zero are included, and the two readings need different tests.",\
                "kind":"CHOICE","options":["whole numbers including zero and negatives",\
                "positive numbers only"]}]}""");
        }
        if (conversation.contains("You are a requirements analyst. From the documents")) {
            return FakeVllm.Reply.text("""
                {"proposals":[{"kind":"ADD","ref":"N1","title":"Multiplication",\
                "rationale":"the document asks for it in the first line",\
                "priority":"HIGH","requirementKind":"FUNCTIONAL","category":"Arithmetic",\
                "text":"The calculator can multiply two whole numbers and return their product.",\
                "criteria":[\
                {"text":"multiplying two positive whole numbers returns their product",\
                "test":"swarm.accept.MultiplyAcceptTest#multipliesTwoPositiveNumbers"},\
                {"text":"multiplying any whole number by zero returns zero",\
                "test":"swarm.accept.MultiplyAcceptTest#zeroGivesZero"}]}]}""");
        }
        if (conversation.contains("planning a delivery backlog")) {
            return FakeVllm.Reply.text("{\"questions\":[]}");
        }
        if (conversation.contains("You are a delivery planner")) {
            return FakeVllm.Reply.text("""
                {"proposals":[{"kind":"ADD","storyKind":"DELIVERY",\
                "title":"Calculator multiplies","delivers":"R1:C1,R1:C2",\
                "narrative":"add a multiply method to the Calculator class",\
                "rationale":"both criteria are about the same method and pass or fail together"}]}""");
        }

        // --- the workflow roles -------------------------------------------------------------------
        if (conversation.contains("design reviewer")) {
            return FakeVllm.Reply.text("{\"approved\": true, \"objections\": []}");
        }
        if (conversation.contains("revising a design")) {
            // Only reached if the reviewer above ever objects; kept so a change there cannot hang.
            return FakeVllm.Reply.text(SCOPED_DESIGN);
        }
        if (conversation.contains("The requirements are FIXED")) {
            return FakeVllm.Reply.text(SCOPED_DESIGN);
        }
        if (conversation.contains("AI planner")) {
            return FakeVllm.Reply.text(PLAN);
        }
        if (conversation.contains("You are a test author")) {
            return FakeVllm.Reply.text(acceptanceTests());
        }
        if (conversation.contains("code-review judge")) {
            return FakeVllm.Reply.text(
                "{\"score\": 0.92, \"rationale\": \"minimal, correct, no other behaviour touched\"}");
        }

        // Anything unrouted is a scripting gap, and a silent "{}" would surface as a mysterious
        // parse failure three stages later.
        return FakeVllm.Reply.text("{\"error\":\"UNROUTED PROMPT — the dress rehearsal has no "
            + "scripted reply for this call\"}");
    }

    private static final String SCOPED_DESIGN = """
        {"decisions":[{"decision":"a pure instance method on Calculator",\
        "rationale":"matches add and subtract; no state to manage"}],\
        "contracts":[{"name":"Calculator.multiply","description":"the product of two ints",\
        "signature":"public int multiply(int a, int b)"}],\
        "risks":[{"description":"overflow beyond int range","severity":"LOW",\
        "mitigation":"the criteria do not require big numbers"}],\
        "missingRequirements":[]}""";

    /**
     * One task claiming BOTH of the story's criteria. Coverage is a violation, not a warning: a
     * plan that leaves a criterion unclaimed is rejected and the run falls back to a single
     * generic task, which would quietly stop being the journey under test.
     */
    private static final String PLAN = """
        {"tasks":[{"id":"t1","title":"Add multiply to Calculator",\
        "instructions":"Add a public method multiply(int a, int b) returning a*b to the Calculator \
        class in src/main/java/com/example/calc/Calculator.java. Change nothing else.",\
        "writeSet":["src/main/java/com/example/calc/Calculator.java"],\
        "readSet":["src/main/java/com/example/calc"],\
        "criterionRefs":["R1:C1","R1:C2"]}],"edges":[]}""";

    /**
     * The acceptance tests, with method names exactly as the criteria name them. They must FAIL
     * before the change — here by not compiling, because Calculator has no multiply yet, which is
     * the ordinary red state for adding a method test-first.
     */
    private static String acceptanceTests() {
        String source = """
            package swarm.accept;

            import com.example.calc.Calculator;
            import org.junit.jupiter.api.Test;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            class MultiplyAcceptTest {

                private final Calculator calculator = new Calculator();

                @Test
                void multipliesTwoPositiveNumbers() {
                    assertEquals(12, calculator.multiply(3, 4));
                }

                @Test
                void multiplyingByZeroGivesZero() {
                    assertEquals(0, calculator.multiply(7, 0));
                    assertEquals(0, calculator.multiply(0, -5));
                }
            }
            """;
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode file = root.putArray("files").addObject();
        file.put("path", "src/test/java/swarm/accept/MultiplyAcceptTest.java");
        file.put("content", source);
        // The author reports what it wrote for what. The report is checked against the files, not
        // trusted: the audit reads the ids out of the source above and matches the criteria's own
        // references against those.
        var wrote = root.putArray("wrote");
        wrote.addObject()
            .put("criterion", "multiplying two positive whole numbers returns their product")
            .put("test", CRITERION_ONE_TEST);
        wrote.addObject()
            .put("criterion", "multiplying any whole number by zero returns zero")
            .put("test", CRITERION_TWO_TEST);
        return root.toString();
    }

    /** The worker's single edit: the whole file, rewritten with multiply added. */
    private static String writeCalculatorArgs() {
        String content = """
            package com.example.calc;

            /** Tiny domain class so the demo repo has real code to compile, test, and extend. */
            public class Calculator {

                public int add(int a, int b) {
                    return a + b;
                }

                public int subtract(int a, int b) {
                    return a - b;
                }

                public int multiply(int a, int b) {
                    return a * b;
                }

                public int divide(int a, int b) {
                    if (b == 0) {
                        throw new ArithmeticException("division by zero");
                    }
                    return a / b;
                }
            }
            """;
        return new ObjectMapper().createObjectNode()
            .put("path", "src/main/java/com/example/calc/Calculator.java")
            .put("content", content)
            .toString();
    }
}
