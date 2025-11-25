/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.retry.limiter.RetryLimiter;
import com.linecorp.armeria.common.logging.RequestLog;

class RetryLimiterExecutorTest {

    private RetryLimiter mockLimiter;
    private ClientRequestContext mockContext;
    private RequestLog mockRequestLog;

    @BeforeEach
    void setUp() {
        mockLimiter = mock(RetryLimiter.class);
        mockContext = mock(ClientRequestContext.class);
        mockRequestLog = mock(RequestLog.class);
    }

    @Test
    void shouldRetry_withNullLimiter_returnsTrue() {
        final boolean result = RetryLimiterExecutor.shouldRetry(null, mockContext, 0);

        assertThat(result).isTrue();
    }

    @Test
    void shouldRetry_withLimiterReturningTrue_returnsTrue() {
        when(mockLimiter.shouldRetry(any(), anyInt())).thenReturn(true);

        final boolean result = RetryLimiterExecutor.shouldRetry(mockLimiter, mockContext, 1);

        assertThat(result).isTrue();
        verify(mockLimiter).shouldRetry(mockContext, 1);
    }

    @Test
    void shouldRetry_withLimiterReturningFalse_returnsFalse() {
        when(mockLimiter.shouldRetry(any(), anyInt())).thenReturn(false);

        final boolean result = RetryLimiterExecutor.shouldRetry(mockLimiter, mockContext, 2);

        assertThat(result).isFalse();
        verify(mockLimiter).shouldRetry(mockContext, 2);
    }

    @Test
    void shouldRetry_withLimiterThrowingException_returnsTrue() {
        when(mockLimiter.shouldRetry(any(), anyInt())).thenThrow(new RuntimeException("Test exception"));

        final boolean result = RetryLimiterExecutor.shouldRetry(mockLimiter, mockContext, 3);

        assertThat(result).isTrue();
        verify(mockLimiter).shouldRetry(mockContext, 3);
    }

    @Test
    void onCompletedAttempt_withNullLimiter_doesNothing() {
        RetryLimiterExecutor.onCompletedAttempt(null, mockContext, mockRequestLog, 0);

        verifyNoInteractions(mockLimiter);
    }

    @Test
    void onCompletedAttempt_withLimiter_callsLimiter() {
        RetryLimiterExecutor.onCompletedAttempt(mockLimiter, mockContext, mockRequestLog, 1);

        verify(mockLimiter).onCompletedAttempt(mockContext, mockRequestLog, 1);
    }

    @Test
    void onCompletedAttempt_withLimiterThrowingException_doesNotPropagateException() {
        doThrow(new RuntimeException("Test exception")).when(mockLimiter)
                .onCompletedAttempt(any(), any(), anyInt());

        // Should not throw an exception
        RetryLimiterExecutor.onCompletedAttempt(mockLimiter, mockContext, mockRequestLog, 2);

        verify(mockLimiter).onCompletedAttempt(mockContext, mockRequestLog, 2);
    }
}
