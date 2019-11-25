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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.slf4j.Logger;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.LogLevel;

class LoggingClientTest {

    @Mock
    private Logger logger;

    private HttpRequest request;

    private ClientRequestContext context;

    private HttpClient delegate;

    @BeforeEach
    void setUp() {
        when(logger.isInfoEnabled()).thenReturn(true);

        request = HttpRequest.of(HttpMethod.GET, "/");
        context = ClientRequestContext.of(request);
        delegate = (ctx, req) -> {
            ctx.logBuilder().endRequest();
            ctx.logBuilder().endResponse();
            return HttpResponse.of(HttpStatus.NO_CONTENT);
        };
    }

    @Test
    void logger() throws Exception {
        // use custom logger
        final LoggingClient customLoggerClient =
                LoggingClient.builder()
                             .logger(logger)
                             .requestLogLevel(LogLevel.INFO)
                             .successfulResponseLogLevel(LogLevel.INFO)
                             .build(delegate);

        customLoggerClient.execute(context, request);

        // verify request log
        verify(logger).info(eq("Request: {}"), argThat((String actLog) -> actLog
                .endsWith("headers=[:method=GET, :path=/]}")));

        // verify response log
        verify(logger).info(eq("Response: {}"), argThat((String actLog) -> actLog
                .endsWith("duration=0ns, headers=[:status=0]}")));

        // use default logger
        final LoggingClient defaultLoggerClient =
                LoggingClient.builder()
                             .requestLogLevel(LogLevel.INFO)
                             .successfulResponseLogLevel(LogLevel.INFO)
                             .build(delegate);

        defaultLoggerClient.execute(context, request);
        verify(logger, never()).info(anyString());
    }
}
