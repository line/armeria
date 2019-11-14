/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.logging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.slf4j.Logger;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.logging.RequestLogListener;

public class LoggingClientTest {
    private static final String REQUEST_FORMAT = "Request: {}";
    private static final String RESPONSE_FORMAT = "Response: {}";

    private static final String REQUEST_LOG = "requestLog";
    private static final String RESPONSE_LOG = "responseLog";

    @Mock
    private Logger logger;

    @Mock
    private HttpRequest request;

    @Mock
    private ClientRequestContext context;

    @Mock
    private RequestLog log;

    @Mock
    private Client<HttpRequest, HttpResponse> delegate;

    @BeforeEach
    void setUp() {
        when(logger.isInfoEnabled()).thenReturn(true);

        when(context.log()).thenReturn(log);

        doAnswer(invocation -> {
            final RequestLogListener listener = invocation.getArgument(0);
            listener.onRequestLog(log);
            return null;
        }).when(log).addListener(isA(RequestLogListener.class), isA(RequestLogAvailability.class));

        when(log.toStringRequestOnly(any(), any(), any())).thenReturn(REQUEST_LOG);
        when(log.toStringResponseOnly(any(), any(), any())).thenReturn(RESPONSE_LOG);
    }

    @Test
    void logger() throws Exception {
        final LoggingClient<HttpRequest, HttpResponse> customLoggerClient =
                LoggingClient.builder()
                             .logger(logger)
                             .requestLogLevel(LogLevel.INFO)
                             .successfulResponseLogLevel(LogLevel.INFO)
                             .build(delegate);

        customLoggerClient.execute(context, request);

        verify(logger).info(REQUEST_FORMAT, REQUEST_LOG);
        verify(logger).info(RESPONSE_FORMAT, RESPONSE_LOG);

        // use default logger
        final LoggingClient<HttpRequest, HttpResponse> defaultLoggerClient =
                LoggingClient.builder()
                             .requestLogLevel(LogLevel.INFO)
                             .successfulResponseLogLevel(LogLevel.INFO)
                             .build(delegate);

        defaultLoggerClient.execute(context, request);
        verify(logger, never()).info(anyString());
    }
}
