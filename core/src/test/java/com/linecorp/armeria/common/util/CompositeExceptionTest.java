/*
 * Copyright 2022 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

class CompositeExceptionTest {

    private static final String separator = System.getProperty("line.separator");
    private static final Sampler<Class<? extends Throwable>> alwaysVerboseExceptionSampler =
            Sampler.always();
    private static final Sampler<Class<? extends Throwable>> neverVerboseExceptionSampler =
            Sampler.never();

    @Test
    void verboseExceptionEnabledTest() {
        final IllegalStateException ex1 = new IllegalStateException();
        final IllegalArgumentException ex2 = new IllegalArgumentException();
        final CompositeException compositeException =
                new CompositeException(ImmutableList.of(ex1, ex2), alwaysVerboseExceptionSampler);

        final CompositeException.ExceptionOverview exceptionOverview =
                (CompositeException.ExceptionOverview) compositeException.getCause();
        final int separatedStacktraceLength =
                (int) Arrays.stream(exceptionOverview.getMessage().split(separator))
                        .filter(line -> !line.contains("Multiple exceptions") && !line.contains("|-- "))
                        .count();

        final int sumStackTraceOutputLength = ex1.getStackTrace().length + ex2.getStackTrace().length;
        // Expected: if verboseException option enabled, output full stacktrace
        // this case is occurred 2 exceptions (ex1 stacktrace length + ex2 stacktrace length)
        assertThat(separatedStacktraceLength).isEqualTo(sumStackTraceOutputLength);
    }

    @Test
    void verboseExceptionDisabledTest() {
        final IllegalStateException ex1 = new IllegalStateException();
        final IllegalArgumentException ex2 = new IllegalArgumentException();
        final CompositeException compositeException =
                new CompositeException(ImmutableList.of(ex1, ex2), neverVerboseExceptionSampler);

        final CompositeException.ExceptionOverview exceptionOverview =
                (CompositeException.ExceptionOverview) compositeException.getCause();
        final int separatedStacktraceLength =
                (int) Arrays.stream(exceptionOverview.getMessage().split(separator))
                        .filter(line -> !line.contains("Multiple exceptions") && !line.contains("|-- "))
                        .count();

        // Expected: if verboseException option disabled, max output stacktrace is 20
        // this case is occurred 2 exceptions (20 * 2)
        assertThat(separatedStacktraceLength).isEqualTo(40);
    }

    @Test
    void verboseExceptionDisabledTestIfStackTraceLengthLessThan20() {
        final IllegalStateException ex1 = new IllegalStateException();
        final IllegalArgumentException ex2 = new IllegalArgumentException();
        final String className = getClass().getSimpleName();
        final String methodName = "verboseExceptionDisabledTestIfStackTraceLengthLessThan20";
        final String fileName = "CompositeExceptionTest.java";
        final StackTraceElement[] customStackTrace = new StackTraceElement[] {
                new StackTraceElement(className, methodName, fileName, 1),
                new StackTraceElement(className, methodName, fileName, 2),
                new StackTraceElement(className, methodName, fileName, 3),
        };
        ex2.setStackTrace(customStackTrace);
        final CompositeException compositeException =
                new CompositeException(ImmutableList.of(ex1, ex2), neverVerboseExceptionSampler);

        final CompositeException.ExceptionOverview exceptionOverview =
                (CompositeException.ExceptionOverview) compositeException.getCause();
        final int separatedStacktraceLength =
                (int) Arrays.stream(exceptionOverview.getMessage().split(separator))
                        .filter(line -> !line.contains("Multiple exceptions") && !line.contains("|-- "))
                        .count();

        // Expected: if verboseException option disabled, max output stacktrace is 20
        // but this case, one exception has 3 stacktrace (less than 20)
        assertThat(separatedStacktraceLength).isEqualTo(23);
    }
}
