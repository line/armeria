/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.channels.ClosedChannelException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ExceptionReportingServiceErrorHandlerTest {

    @Mock
    private ServiceErrorHandler delegate;
    @Mock
    private UnhandledExceptionsReporter reporter;
    @Mock
    private ServiceRequestContext ctx;

    private ExceptionReportingServiceErrorHandler serviceErrorHandler;

    @BeforeEach
    void setUp() {
        reset(reporter);
        reset(ctx);
        serviceErrorHandler = new ExceptionReportingServiceErrorHandler(delegate, reporter);
    }

    @Test
    void onServiceExceptionShouldNotReportWhenShouldReportUnhandledExceptionsIsFalse() {
        when(ctx.shouldReportUnhandledExceptions()).thenReturn(false);
        serviceErrorHandler.onServiceException(ctx, new IllegalArgumentException("Test"));
        verify(reporter, times(0)).report(any());
    }

    @Test
    void onServiceExceptionShouldNotReportWhenCauseIsExpected() {
        when(ctx.shouldReportUnhandledExceptions()).thenReturn(true);
        serviceErrorHandler.onServiceException(ctx, new ClosedChannelException());
        verify(reporter, times(0)).report(any());
    }

    @Test
    void onServiceExceptionShouldReportWhenCauseIsNotExpected() {
        when(ctx.shouldReportUnhandledExceptions()).thenReturn(true);
        serviceErrorHandler.onServiceException(ctx, new IllegalArgumentException("Test"));
        verify(reporter, times(1)).report(any());
    }

    @Test
    void onServiceExceptionShouldNotReportWhenCauseIsHttpStatusExceptionAndCauseNull() {
        when(ctx.shouldReportUnhandledExceptions()).thenReturn(true);
        serviceErrorHandler.onServiceException(ctx, HttpStatusException.of(500));
        verify(reporter, times(0)).report(any());
    }

    @Test
    void onServiceExceptionShouldReportWhenCauseIsHttpStatusExceptionAndCauseNonNull() {
        when(ctx.shouldReportUnhandledExceptions()).thenReturn(true);
        serviceErrorHandler.onServiceException(
                ctx, HttpStatusException.of(500, new IllegalArgumentException("test")));
        verify(reporter, times(1)).report(any());
    }
}
