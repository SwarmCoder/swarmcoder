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
package com.swarmcoder.workflow;

import java.util.List;

/**
 * The message shape for the second way a freshly authored acceptance test "does not compile":
 * not because the code it needs has not been written yet (a healthy red), but because it names a
 * type or package nobody in this plan either promises or may write ({@link
 * com.swarmcoder.verify.TypeDeliverability}).
 *
 * <h2>The run this exists because of</h2>
 *
 * <p>Harness run 26, 08:17. The story's third wave owned a screen check; the test author wrote it
 * in {@code bookshelf-demo-server} — the module {@code AcceptanceTestLocation} chooses because it
 * is the one whose test classpath reaches every other module — and imported {@code
 * com.zeroz4j.ui.*}, a package that exists only in the CLIENT module, which the server module does
 * not depend on. {@link AcceptanceTestVocabulary} let it through: the package exists SOMEWHERE in
 * the checkout, just not on this module's own classpath, and that check never asked "can this
 * module actually see it?" Every gate downstream read "does not compile" as red, exactly as it
 * would for a test written before its code, and every candidate of that wave died for a test that
 * could never compile there.
 *
 * <h2>The rule this class carries the wording for</h2>
 *
 * <p>Told once, with the error and the module's own classpath, a test author can fix this itself —
 * it is not asked to guess at a boundary it was never shown. Asked a second time and still wrong,
 * the run parks: this is the same one-bounded-attempt shape as {@link AcceptanceTestVocabulary}'s
 * re-ask and {@code TestAuthorClient}'s empty-reply re-ask.
 */
public final class BrokenAcceptanceTest {

    private BrokenAcceptanceTest() {}

    /**
     * What the test author is told when its test names something this plan will never deliver and
     * this module cannot see — sent once, before the run parks.
     *
     * @param undeliverable the missing names — types or packages — that nobody in the plan
     *                      delivers and no task's write set covers
     * @param module        the acceptance module the test lives in, repo-relative; {@code ""} for
     *                      the repository root
     * @param classpath     the artifact ids that module's own build file declares — everything a
     *                      test written there can actually reach
     */
    public static String reask(List<String> undeliverable, String module, List<String> classpath) {
        StringBuilder names = new StringBuilder();
        for (String name : undeliverable) {
            names.append(names.isEmpty() ? "" : ", ").append('`').append(name).append('`');
        }
        StringBuilder message = new StringBuilder("Your test does not compile, and not because the "
            + "code it needs has not been written yet — that would be a healthy red state. ")
            .append(names)
            .append(undeliverable.size() == 1 ? " is not a contract this plan delivers and no task's "
                : " are not contracts this plan delivers and no task's ")
            .append("write set can create ").append(undeliverable.size() == 1 ? "it" : "them")
            .append(". This is a broken test, not TDD.\n\n")
            .append(classpathSentence(module, classpath))
            .append("\n\nProve the check using only what this module can actually see — a module it "
                + "depends on, or the types this plan's contracts add here. A criterion about what a "
                + "person SEES on screen is proved by this project's browser stage, not by importing "
                + "the screen's own package into a JUnit test that cannot open one.\n\n"
                + "Reply with the same JSON object, with the corrected file(s).");
        return message.toString();
    }

    /**
     * The brief a run parks with when the test author was asked once, per {@link #reask}, and its
     * correction still names something this module cannot deliver or see.
     */
    public static String park(String taskTitle, List<String> undeliverable, String module,
                              List<String> classpath, String secondAttemptNote) {
        StringBuilder names = new StringBuilder();
        for (String name : undeliverable) {
            names.append(names.isEmpty() ? "" : ", ").append('`').append(name).append('`');
        }
        StringBuilder sb = new StringBuilder("The acceptance test(s) for task '").append(taskTitle)
            .append("' name ").append(undeliverable.size() == 1 ? "a type or package" : "types or packages")
            .append(" this plan does not deliver and this module cannot see: ").append(names)
            .append(". The test author was asked once to correct this and did not")
            .append(secondAttemptNote == null || secondAttemptNote.isBlank() ? "."
                : ": " + secondAttemptNote.strip() + ".")
            .append("\n\n").append(classpathSentence(module, classpath))
            .append("\n\nThis is not a healthy red state: \"does not compile\" reads the same "
                + "whether the code has not been written yet or the test simply cannot reach what it "
                + "names, so nothing further down this run can tell the two apart. Dispatching a "
                + "swarm now would fail every candidate for a tree that none of them broke.\n\n"
                + "Decide which side is wrong: add the missing type to the design as a contract this "
                + "module can reach, or correct the test to prove the check through what this module "
                + "actually depends on. Then resume the run.");
        return sb.toString();
    }

    private static String classpathSentence(String module, List<String> classpath) {
        String where = module == null || module.isBlank() ? "the repository root" : module;
        String artifacts = classpath == null || classpath.isEmpty() ? "no declared dependencies"
            : String.join(", ", classpath);
        return "The acceptance module is " + where + "; its classpath holds: " + artifacts + ".";
    }
}
