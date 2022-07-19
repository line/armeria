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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.internal.util.reflection.ReflectionMemberAccessor;
import org.mockito.junit.jupiter.MockitoExtension;

import com.linecorp.armeria.common.Flags;

@ExtendWith(MockitoExtension.class)
class CompositeExceptionTest {

    private static final String separator = System.getProperty("line.separator");

    private CompositeException compositeException;

    @Mock
    private final Sampler<Class<? extends Throwable>> verboseExceptionFlag = Flags.verboseExceptionSampler();

    @BeforeEach
    void init() throws Exception {
        MockitoAnnotations.openMocks(this);

        final List<Throwable> exceptions =
                Arrays.asList(new IllegalStateException(), new NullPointerException());
        compositeException = new CompositeException(exceptions);
        // Mocking verboseException environment
        final Field samplerField =
                compositeException.getClass().getDeclaredField("verboseExceptionFlag");
        final ReflectionMemberAccessor mockitoMemberAccessor = new ReflectionMemberAccessor();
        modifyFinalStaticFieldForSampler(samplerField, verboseExceptionFlag);
    }

    @Test
    void verboseExceptionOptionDisabledTest() {
        when(verboseExceptionFlag.isSampled(any())).thenReturn(false);

        final CompositeException.ExceptionOverview exceptionOverview =
                (CompositeException.ExceptionOverview) compositeException.getCause();
        final List<String> separatedStacktrace =
                Arrays.stream(exceptionOverview.getMessage().split(separator))
                      .filter(line -> !line.contains("Multiple exceptions") && !line.contains("|-- "))
                      .collect(Collectors.toList());

        // Expected: if verboseException option disabled, output 1 top stacktrace
        // this case is occurred 2 exceptions (1 * 2)
        assertThat(separatedStacktrace.size()).isEqualTo(2);
    }

    @Test
    void verboseExceptionOptionEnabledTest() {
        when(verboseExceptionFlag.isSampled(any())).thenReturn(true);

        final CompositeException.ExceptionOverview exceptionOverview =
                (CompositeException.ExceptionOverview) compositeException.getCause();
        final List<String> separatedStacktrace =
                Arrays.stream(exceptionOverview.getMessage().split(separator))
                      .filter(line -> !line.contains("Multiple exceptions") && !line.contains("|-- "))
                      .collect(Collectors.toList());

        // Expected: if verboseException option enabled, max output stacktrace is 20
        // this case is occurred 2 exceptions (20 * 2)
        assertThat(separatedStacktrace.size()).isEqualTo(40);
    }

    private static void modifyFinalStaticFieldForSampler(final Field field,
                                                         final Object newValue) throws Exception {
        field.setAccessible(true);
        final Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        field.set(null, newValue);
    }
}
