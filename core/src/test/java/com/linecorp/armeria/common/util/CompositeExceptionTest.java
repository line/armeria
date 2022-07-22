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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import com.linecorp.armeria.common.Flags;

class CompositeExceptionTest {

    private static final String separator = System.getProperty("line.separator");

    @Mock
    Sampler<Class<? extends Throwable>> sampler = Flags.verboseExceptionSampler();

    @Test
    void verboseExceptionEnabledTest() {
        final IllegalStateException ex1 = new IllegalStateException();
        final IllegalArgumentException ex2 = new IllegalArgumentException();
        final CompositeException compositeException = new CompositeException(
                Arrays.asList(ex1, ex2), sampler);

        when(sampler.isSampled(any())).thenReturn(true);

        final CompositeException.ExceptionOverview exceptionOverview =
                (CompositeException.ExceptionOverview) compositeException.getCause();
        final List<String> separatedStacktrace =
                Arrays.stream(exceptionOverview.getMessage().split(separator))
                      .filter(line -> !line.contains("Multiple exceptions") && !line.contains("|-- "))
                      .collect(Collectors.toList());

        final int sumStackTraceOutputLength = ex1.getStackTrace().length + ex2.getStackTrace().length;
        // Expected: if verboseException option enabled, output full stacktrace
        // this case is occurred 2 exceptions (ex1 stacktrace length + ex2 stacktrace length)
        assertThat(separatedStacktrace.size()).isEqualTo(sumStackTraceOutputLength);
    }

    @Test
    void verboseExceptionDisabledTest() {
        final IllegalStateException ex1 = new IllegalStateException();
        final IllegalArgumentException ex2 = new IllegalArgumentException();
        final CompositeException compositeException = new CompositeException(
                Arrays.asList(ex1, ex2), sampler);

        when(sampler.isSampled(any())).thenReturn(false);

        final CompositeException.ExceptionOverview exceptionOverview =
                (CompositeException.ExceptionOverview) compositeException.getCause();
        final List<String> separatedStacktrace =
                Arrays.stream(exceptionOverview.getMessage().split(separator))
                      .filter(line -> !line.contains("Multiple exceptions") && !line.contains("|-- "))
                      .collect(Collectors.toList());

        // Expected: if verboseException option disabled, max output stacktrace is 20
        // this case is occurred 2 exceptions (20 * 2)
        assertThat(separatedStacktrace.size()).isEqualTo(40);
    }
}
