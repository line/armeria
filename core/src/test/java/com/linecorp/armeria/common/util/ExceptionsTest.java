/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import org.junit.Test;

public class ExceptionsTest {

    @Test
    public void testPeel() {
        final IllegalArgumentException originalException = new IllegalArgumentException();

        // There's nothing to peel.
        assertThat(Exceptions.peel(originalException)).isSameAs(originalException);

        // Wrap the exception with a CompletionException.
        final CompletionException completionException = new CompletionException(originalException);
        assertThat(Exceptions.peel(completionException)).isSameAs(originalException);

        // Wrap the CompletionException with the same ExecutionException.
        final CompletionException wrappedTwice = new CompletionException(completionException);
        assertThat(Exceptions.peel(wrappedTwice)).isSameAs(originalException);

        // Wrap the CompletionException with a ExecutionException.
        final ExecutionException executionException = new ExecutionException(completionException);
        assertThat(Exceptions.peel(executionException)).isSameAs(originalException);

        // Wrap the CompletionException with a InvocationTargetException.
        final InvocationTargetException invocationTargetException = new InvocationTargetException(
                executionException);
        assertThat(Exceptions.peel(invocationTargetException)).isSameAs(originalException);

        // Wrap the CompletionException with a ExceptionInInitializerError.
        final ExceptionInInitializerError exceptionInInitializerError = new ExceptionInInitializerError(
                executionException);
        assertThat(Exceptions.peel(exceptionInInitializerError)).isSameAs(originalException);
    }

    /**
     * Should pass on both Windows and *nix because {@link Exceptions#traceText(Throwable)} is expected to
     * always use {@code '\n'} as a line delimiter.
     */
    @Test
    public void testTraceText() {
        final String trace = Exceptions.traceText(new Exception("foo"));
        assertThat(trace).startsWith(Exception.class.getName() + ": foo\n" +
                                     "\tat " + ExceptionsTest.class.getName());
    }
}
