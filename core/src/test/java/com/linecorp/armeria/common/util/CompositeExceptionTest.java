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

import com.linecorp.armeria.internal.testing.AnticipatedException;

class CompositeExceptionTest {

    private static final String separator = System.getProperty("line.separator");

    @Test
    void verboseExceptionEnabledTest() {
        final IllegalStateException ex1 = new IllegalStateException();
        final IllegalArgumentException ex2 = new IllegalArgumentException();
        final CompositeException compositeException =
                new CompositeException(ImmutableList.of(ex1, ex2), Sampler.always());
        final long separatedStacktraceLength = stackTraceLineLength(compositeException);
        final int stackTraceLength = sumStackTraces(Sampler.always(), ex1, ex2);

        // if verboseException option enabled, output full stacktrace
        // this case is occurred 2 exceptions (ex1 stacktrace length + ex2 stacktrace length)
        assertThat(separatedStacktraceLength).isEqualTo(stackTraceLength);
    }

    @Test
    void verboseExceptionDisabledTest() {
        final IllegalStateException ex1 = new IllegalStateException();
        final IllegalArgumentException ex2 = new IllegalArgumentException();
        final CompositeException compositeException =
                new CompositeException(ImmutableList.of(ex1, ex2), Sampler.never());
        final long separatedStacktraceLength = stackTraceLineLength(compositeException);
        final int stackTraceLength = sumStackTraces(Sampler.never(), ex1, ex2);

        // if verboseException option disabled, max output stacktrace is
        // CompositeException.DEFAULT_MAX_NUM_STACK_TRACES.
        // this case is occurred 2 exceptions (20 * 2)
        assertThat(separatedStacktraceLength).isEqualTo(stackTraceLength);
    }

    @Test
    void verboseExceptionDisabledTestIfStackTraceLengthLessThan20() {
        final IllegalStateException ex1 = new IllegalStateException();
        final IllegalArgumentException ex2 = new IllegalArgumentException();
        final String className = getClass().getSimpleName();
        final String methodName = "verboseExceptionDisabledTestIfStackTraceLengthLessThan20";
        final String fileName = "CompositeExceptionTest.java";
        final StackTraceElement[] customStackTrace = {
                new StackTraceElement(className, methodName, fileName, 1),
                new StackTraceElement(className, methodName, fileName, 2),
                new StackTraceElement(className, methodName, fileName, 3),
        };
        ex2.setStackTrace(customStackTrace);
        final CompositeException compositeException =
                new CompositeException(ImmutableList.of(ex1, ex2), Sampler.never());
        final long separatedStacktraceLength = stackTraceLineLength(compositeException);
        final int stackTraceLength = sumStackTraces(Sampler.never(), ex1, ex2);

        // if verboseException option disabled, max output stacktrace is
        // CompositeException.DEFAULT_MAX_NUM_STACK_TRACES.
        // but this case, one exception has 3 stacktrace (less than 20)
        assertThat(separatedStacktraceLength).isEqualTo(stackTraceLength);
    }

    @Test
    void verboseExceptionEnabledTestComposite() {
        final IllegalStateException ex1 = new IllegalStateException();
        final IllegalArgumentException ex2 = new IllegalArgumentException();
        final AnticipatedException ex3 = new AnticipatedException(ex2);
        final CompositeException compositeException =
                new CompositeException(ImmutableList.of(ex1, ex3), Sampler.always());
        final long separatedStacktraceLength = stackTraceLineLength(compositeException);
        final int stackTraceLength = sumStackTraces(Sampler.always(), ex1, ex2, ex3);

        // if verboseException option enabled, test for composite exceptions.
        // Expected: ex1 StackTraces + ex2 StackTraces + ex3 StackTraces
        assertThat(separatedStacktraceLength).isEqualTo(stackTraceLength);
    }

    @Test
    void verboseExceptionEnabledTestMultipleContainsCustomStackTraces() {
        final IllegalStateException ex1 = new IllegalStateException();
        final IllegalArgumentException ex2 = new IllegalArgumentException();
        final AnticipatedException ex3 = new AnticipatedException(ex2);
        final String className = getClass().getSimpleName();
        final String methodName = "verboseExceptionDisabledTestIfStackTraceLengthLessThan20";
        final String fileName = "CompositeExceptionTest.java";
        final StackTraceElement[] customStackTrace = {
                new StackTraceElement(className, methodName, fileName, 1),
                new StackTraceElement(className, methodName, fileName, 2),
                new StackTraceElement(className, methodName, fileName, 3),
        };
        ex2.setStackTrace(customStackTrace);
        final CompositeException compositeException =
                new CompositeException(ImmutableList.of(ex1, ex3), Sampler.always());
        final long separatedStacktraceLength = stackTraceLineLength(compositeException);
        final int stackTraceLength = sumStackTraces(Sampler.always(), ex1, ex2, ex3);

        // if verboseException option enabled, test for composite exceptions.
        // Expected: ex1 StackTraces + ex2 StackTraces(3) + ex3 StackTraces
        assertThat(separatedStacktraceLength).isEqualTo(stackTraceLength);
    }

    @Test
    void verboseExceptionDisabledTestMultiple() {
        final IllegalStateException ex1 = new IllegalStateException();
        final IllegalArgumentException ex2 = new IllegalArgumentException();
        final AnticipatedException ex3 = new AnticipatedException(ex2);
        final CompositeException compositeException =
                new CompositeException(ImmutableList.of(ex1, ex3), Sampler.never());
        final long separatedStacktraceLength = stackTraceLineLength(compositeException);
        final int stackTraceLength = sumStackTraces(Sampler.never(), ex1, ex2, ex3);

        // if verboseException option enabled, test for composite exceptions.
        // Expected: CompositeException.DEFAULT_MAX_NUM_STACK_TRACES * 3
        assertThat(separatedStacktraceLength).isEqualTo(stackTraceLength);
    }

    @Test
    void verboseExceptionDisabledTestMultipleContainsCustomStackTraces() {
        final IllegalStateException ex1 = new IllegalStateException();
        final IllegalArgumentException ex2 = new IllegalArgumentException();
        final AnticipatedException ex3 = new AnticipatedException(ex2);
        final String className = getClass().getSimpleName();
        final String methodName = "verboseExceptionDisabledTestIfStackTraceLengthLessThan20";
        final String fileName = "CompositeExceptionTest.java";
        final StackTraceElement[] customStackTrace = {
                new StackTraceElement(className, methodName, fileName, 1),
                new StackTraceElement(className, methodName, fileName, 2),
                new StackTraceElement(className, methodName, fileName, 3),
        };
        ex2.setStackTrace(customStackTrace);
        final CompositeException compositeException =
                new CompositeException(ImmutableList.of(ex1, ex3), Sampler.never());
        final long separatedStacktraceLength = stackTraceLineLength(compositeException);
        final int stackTraceLength = sumStackTraces(Sampler.never(), ex1, ex2, ex3);

        // if verboseException option enabled, test for composite exceptions.
        // Expected: CompositeException.DEFAULT_MAX_NUM_STACK_TRACES * 3 + ex2 StackTraces(3)
        assertThat(separatedStacktraceLength).isEqualTo(stackTraceLength);
    }

    @Test
    void paddingTest() {
        final IllegalStateException ex1 = new IllegalStateException();
        final IllegalArgumentException ex2 = new IllegalArgumentException();
        final String className = getClass().getSimpleName();
        final String methodName = "verboseExceptionDisabledTestIfStackTraceLengthLessThan20";
        final String fileName = "CompositeExceptionTest.java";
        final StackTraceElement[] customStackTrace = {
                new StackTraceElement(className, methodName, fileName, 1),
                new StackTraceElement(className, methodName, fileName, 2),
                new StackTraceElement(className, methodName, fileName, 3),
        };
        ex1.setStackTrace(customStackTrace);
        ex2.setStackTrace(customStackTrace);
        final CompositeException compositeException =
                new CompositeException(ImmutableList.of(ex1, ex2), Sampler.never());
        final int paddingCount = paddingCount(compositeException);

        // padding length * stack trace length * exception amount
        final int expectPaddingCount = 4 * 3 * 2;
        assertThat(paddingCount).isEqualTo(expectPaddingCount);
    }

    @Test
    void compositeExceptionPaddingTest() {
        final IllegalStateException ex1 = new IllegalStateException();
        final IllegalArgumentException ex2 = new IllegalArgumentException();
        final AnticipatedException ex3 = new AnticipatedException(ex2);
        final String className = getClass().getSimpleName();
        final String methodName = "verboseExceptionDisabledTestIfStackTraceLengthLessThan20";
        final String fileName = "CompositeExceptionTest.java";
        final StackTraceElement[] customStackTrace = {
                new StackTraceElement(className, methodName, fileName, 1),
                new StackTraceElement(className, methodName, fileName, 2),
                new StackTraceElement(className, methodName, fileName, 3),
        };
        ex1.setStackTrace(customStackTrace);
        ex2.setStackTrace(customStackTrace);
        ex3.setStackTrace(customStackTrace);
        final CompositeException compositeException =
                new CompositeException(ImmutableList.of(ex1, ex3), Sampler.never());
        final int paddingCount = paddingCount(compositeException);

        // padding * custom stack trace length * exception amount + ( composite exception padding length *
        // composite exception custom stack trace length )
        final int expectPaddingCount = 4 * 3 * 2 + (6 * 3);
        assertThat(paddingCount).isEqualTo(expectPaddingCount);
    }

    private static int paddingCount(final CompositeException exception) {
        return Arrays.stream(exception.getCause().getMessage().split(separator))
                     .filter(line -> !line.contains("Multiple exceptions") && !line.contains("|-- "))
                     .mapToInt(line -> {
                         for (int i = 0; i < line.length(); i++) {
                             if (line.charAt(i) != ' ') {
                                 return i;
                             }
                         }
                         return 0;
                     })
                     .sum();
    }

    private static long stackTraceLineLength(final CompositeException exception) {
        return Arrays.stream(exception.getCause().getMessage().split(separator))
                     .filter(line -> !line.contains("Multiple exceptions") && !line.contains("|-- "))
                     .count();
    }

    private static int sumStackTraces(final Sampler<Class<? extends Throwable>> verboseExceptionSampler,
                                      final Throwable... exceptions) {
        return Arrays.stream(exceptions)
                     .mapToInt(exception -> {
                         final boolean isSampled = verboseExceptionSampler.isSampled(exception.getClass());
                         final int stackTraceLength = exception.getStackTrace().length;
                         if (isSampled || stackTraceLength < CompositeException.DEFAULT_MAX_NUM_STACK_TRACES) {
                             return stackTraceLength;
                         }
                         return CompositeException.DEFAULT_MAX_NUM_STACK_TRACES;
                     })
                     .sum();
    }
}
