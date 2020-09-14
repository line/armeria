/*
 * Copyright 2019 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RegexBasedSanitizer;
import com.linecorp.armeria.internal.common.logging.LoggingTestUtil;
import com.linecorp.armeria.internal.testing.AnticipatedException;

class LoggingClientTest {
    private static final HttpClient delegate = (ctx, req) -> {
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse();
        return HttpResponse.of(HttpStatus.NO_CONTENT);
    };

    private final AtomicReference<Throwable> capturedCause = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        LoggingTestUtil.throwIfCaptured(capturedCause);
    }

    @Test
    void logger() throws Exception {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = ClientRequestContext.of(req);

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isInfoEnabled()).thenReturn(true);

        // use custom logger
        final LoggingClient customLoggerClient =
                LoggingClient.builder()
                             .logger(logger)
                             .requestLogLevel(LogLevel.INFO)
                             .successfulResponseLogLevel(LogLevel.INFO)
                             .build(delegate);

        customLoggerClient.execute(ctx, req);

        verify(logger, times(2)).isInfoEnabled();

        // verify request log
        verify(logger).info(eq("{} Request: {}"), eq(ctx),
                            argThat((String actLog) -> actLog.endsWith("headers=[:method=GET, :path=/]}")));

        // verify response log
        verify(logger).info(eq("{} Response: {}"), eq(ctx),
                            argThat((String actLog) -> actLog.endsWith("headers=[:status=0]}")));

        verifyNoMoreInteractions(logger);
        clearInvocations(logger);

        // use default logger
        final LoggingClient defaultLoggerClient =
                LoggingClient.builder()
                             .requestLogLevel(LogLevel.INFO)
                             .successfulResponseLogLevel(LogLevel.INFO)
                             .build(delegate);

        defaultLoggerClient.execute(ctx, req);
        verifyNoInteractions(logger);
    }

    @Test
    void sanitizeRequestHeaders() throws Exception {
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/trustin",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "test.com"));

        final ClientRequestContext ctx = ClientRequestContext.of(req);

        // use default logger
        final LoggingClient defaultLoggerClient =
                LoggingClient.builder()
                             .requestLogLevel(LogLevel.INFO)
                             .successfulResponseLogLevel(LogLevel.INFO)
                             .requestHeadersSanitizer(RegexBasedSanitizer.of(
                                     Pattern.compile("trustin"),
                                     Pattern.compile("com")))
                             .build(delegate);

        // Pre sanitize step
        assertThat(ctx.logBuilder().toString()).contains("trustin");
        assertThat(ctx.logBuilder().toString()).contains("test.com");
        defaultLoggerClient.execute(ctx, req);
        // After the sanitize
        assertThat(ctx.logBuilder().toString()).doesNotContain("trustin");
        assertThat(ctx.logBuilder().toString()).doesNotContain("com");
    }

    @Test
    void sanitizeRequestContent() throws Exception {
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/trustin",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "test.com"));

        final ClientRequestContext ctx = ClientRequestContext.of(req);
        ctx.logBuilder().requestContent("Virginia 333-490-4499", "Virginia 333-490-4499");

        // use default logger
        final LoggingClient defaultLoggerClient =
                LoggingClient.builder()
                             .requestLogLevel(LogLevel.INFO)
                             .successfulResponseLogLevel(LogLevel.INFO)
                             .requestContentSanitizer(RegexBasedSanitizer.of(
                                     Pattern.compile("\\d{3}[-\\.\\s]\\d{3}[-\\.\\s]\\d{4}")))
                             .build(delegate);

        // Before sanitize content
        assertThat(ctx.logBuilder().toString()).contains("333-490-4499");
        defaultLoggerClient.execute(ctx, req);
        // Ensure sanitize the request content of the phone number 333-490-4499
        assertThat(ctx.logBuilder().toString()).doesNotContain("333-490-4499");
    }

    @Test
    void exceptionWhileLogging() throws Exception {
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/trustin",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "test.com"));
        final ClientRequestContext ctx = ClientRequestContext.of(req);
        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        final LoggingClient loggingClient = LoggingClient.builder()
                                                         .logger(logger)
                                                         .requestLogLevelMapper(log -> {
                                                             throw new AnticipatedException();
                                                         })
                                                         .responseLogLevelMapper(log -> {
                                                             throw new AnticipatedException();
                                                         })
                                                         .build(delegate);
        loggingClient.execute(ctx, req);
        verify(logger).warn(eq("{} Unexpected exception while logging request: "), eq(ctx),
                            any(CompletionException.class));
        verify(logger).warn(eq("{} Unexpected exception while logging response: "), eq(ctx),
                            any(CompletionException.class));
        verifyNoMoreInteractions(logger);
        clearInvocations(logger);
    }
}
