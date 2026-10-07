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
package com.swarmcoder.verify;

import com.swarmcoder.domain.TestResults;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Arrays;

class JUnitXmlParserTest {

    private static final String SUREFIRE_REPORT = """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="com.example.CalcTest" tests="4" failures="1" errors="1" skipped="1" time="0.12">
          <testcase classname="com.example.CalcTest" name="adds" time="0.01"/>
          <testcase classname="com.example.CalcTest" name="subtracts" time="0.01">
            <failure message="expected 1 but was 2" type="org.opentest4j.AssertionFailedError">stack trace here</failure>
          </testcase>
          <testcase classname="com.example.CalcTest" name="divides" time="0.01">
            <error message="ArithmeticException" type="java.lang.ArithmeticException">divide by zero</error>
          </testcase>
          <testcase classname="com.example.CalcTest" name="multiplies" time="0.0">
            <skipped/>
          </testcase>
        </testsuite>
        """;

    @Test
    void parsesCountsAndFailureDetails() {
        TestResults results = JUnitXmlParser.parse(List.of(SUREFIRE_REPORT));

        assertThat(results.passed()).isEqualTo(1);
        assertThat(results.failed()).isEqualTo(1);
        assertThat(results.errored()).isEqualTo(1);
        assertThat(results.skipped()).isEqualTo(1);
        assertThat(results.failures()).hasSize(2);
        assertThat(results.failures().get(0).testId()).isEqualTo("com.example.CalcTest#subtracts");
        assertThat(results.failures().get(0).message()).contains("expected 1 but was 2");
        assertThat(results.failures().get(0).truncatedTrace()).contains("stack trace here");
    }

    @Test
    void mergesMultipleReportFiles() {
        String secondSuite = """
            <testsuite name="com.example.OtherTest" tests="2" failures="0" errors="0" skipped="0">
              <testcase classname="com.example.OtherTest" name="a"/>
              <testcase classname="com.example.OtherTest" name="b"/>
            </testsuite>
            """;
        TestResults results = JUnitXmlParser.parse(List.of(SUREFIRE_REPORT, secondSuite));

        assertThat(results.passed()).isEqualTo(3);
        assertThat(results.failed()).isEqualTo(1);
    }

    @Test
    void unparseableReportCountsAsError() {
        TestResults results = JUnitXmlParser.parse(List.of("not xml at all <<<"));

        assertThat(results.errored()).isEqualTo(1);
        assertThat(results.failures()).hasSize(1);
        assertThat(results.failures().get(0).testId()).isEqualTo("report-parse-error");
    }

    @Test
    void blankAndNullContentsAreIgnored() {
        TestResults results = JUnitXmlParser.parse(Arrays.asList(null, "", "  "));

        assertThat(results.passed()).isZero();
        assertThat(results.errored()).isZero();
    }

    // Harness run 22, 00:50: a candidate compiled and its one claimed acceptance test threw, and
    // nothing downstream said which exception, in which method, at which line. These pin the fix
    // at its source: the trace kept on TestFailure must name the candidate's own code and must
    // drop the JUnit/JDK plumbing around it, or every consumer built on it inherits the same gap.
    private static final String NPE_ERROR_XML = """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="swarm.accept.BookRatingTest" tests="1" failures="0" errors="1" skipped="0">
          <testcase classname="swarm.accept.BookRatingTest" name="assignsRatingToBook" time="0.02">
            <error message="Cannot invoke &quot;String.length()&quot; because &quot;title&quot; is null" type="java.lang.NullPointerException">java.lang.NullPointerException: Cannot invoke "String.length()" because "title" is null
        \tat com.swarmcoder.demo.bookshelf.server.BookServiceServerImpl.assignRating(BookServiceServerImpl.java:41)
        \tat swarm.accept.BookRatingTest.assignsRatingToBook(BookRatingTest.java:23)
        \tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
        \tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:77)
        \tat org.junit.platform.commons.util.ReflectionUtils.invokeMethod(ReflectionUtils.java:728)
        \tat org.junit.jupiter.engine.execution.MethodInvocation.proceed(MethodInvocation.java:60)</error>
          </testcase>
        </testsuite>
        """;

    @Test
    void keepsTheCandidatesOwnFrameAndDropsJunitAndJdkPlumbing() {
        TestResults results = JUnitXmlParser.parse(List.of(NPE_ERROR_XML));

        assertThat(results.errored()).isEqualTo(1);
        var failure = results.failures().get(0);
        assertThat(failure.testId()).isEqualTo("swarm.accept.BookRatingTest#assignsRatingToBook");
        assertThat(failure.message()).contains("title").contains("is null");
        assertThat(failure.truncatedTrace())
            .contains("java.lang.NullPointerException")
            .contains("BookServiceServerImpl.assignRating(BookServiceServerImpl.java:41)")
            .contains("BookRatingTest.assignsRatingToBook(BookRatingTest.java:23)")
            .doesNotContain("NativeMethodAccessorImpl")
            .doesNotContain("ReflectionUtils.invokeMethod")
            .doesNotContain("MethodInvocation.proceed");
    }

    @Test
    void capsKeptFramesAtTwelve() {
        StringBuilder trace = new StringBuilder("java.lang.RuntimeException: boom\n");
        for (int i = 0; i < 20; i++) {
            trace.append("\tat com.swarmcoder.demo.Level").append(i)
                .append(".call(Level").append(i).append(".java:").append(i + 1).append(")\n");
        }
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="swarm.accept.DeepTest" tests="1" failures="0" errors="1" skipped="0">
              <testcase classname="swarm.accept.DeepTest" name="deep" time="0.02">
                <error message="boom" type="java.lang.RuntimeException">%s</error>
              </testcase>
            </testsuite>
            """.formatted(trace);

        TestResults results = JUnitXmlParser.parse(List.of(xml));

        String kept = results.failures().get(0).truncatedTrace();
        long frameLines = kept.lines().filter(l -> l.stripLeading().startsWith("at ")).count();
        assertThat(frameLines).isEqualTo(12);
        assertThat(kept).contains("Level0.call").contains("Level11.call")
            .doesNotContain("Level12.call");
    }
}
