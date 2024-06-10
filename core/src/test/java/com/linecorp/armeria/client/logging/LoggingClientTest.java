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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

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
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.LogFormatter;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.logging.RegexBasedSanitizer;
import com.linecorp.armeria.internal.common.logging.LoggingTestUtil;
import com.linecorp.armeria.internal.testing.ImmediateEventLoop;

class LoggingClientTest {
    static final HttpClient delegate = (ctx, req) -> {
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse();
        return HttpResponse.of(HttpStatus.NO_CONTENT);
    };

    private final AtomicReference<Throwable> capturedCause = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        LoggingTestUtil.throwIfCaptured(capturedCause);
    }

    static ClientRequestContext clientRequestContext(HttpRequest req) {
        return ClientRequestContext.builder(req)
                                   .eventLoop(ImmediateEventLoop.INSTANCE)
                                   .build();
    }

    @Test
    void logger() throws Exception {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = clientRequestContext(req);

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isInfoEnabled()).thenReturn(true);

        // use custom logger
        final LoggingClient customLoggerClient =
                LoggingClient.builder()
                             .logWriter(LogWriter.builder()
                                                 .logger(logger)
                                                 .requestLogLevel(LogLevel.INFO)
                                                 .successfulResponseLogLevel(LogLevel.INFO)
                                                 .build())
                             .build(delegate);

        customLoggerClient.execute(ctx, req);

        verify(logger, times(2)).isInfoEnabled();

        // verify request log
        verify(logger).info(argThat((String actLog) -> actLog.contains("Request:") &&
                                                       actLog.endsWith("headers=[:method=GET, :path=/]}")));

        // verify response log
        verify(logger).info(argThat((String actLog) -> actLog.contains("Response:") &&
                                                       actLog.endsWith("headers=[:status=0]}")));

        verifyNoMoreInteractions(logger);
        clearInvocations(logger);

        // use default logger
        final LoggingClient defaultLoggerClient =
                LoggingClient.builder()
                             .logWriter(LogWriter.builder()
                                                 .requestLogLevel(LogLevel.INFO)
                                                 .successfulResponseLogLevel(LogLevel.INFO)
                                                 .build())
                             .build(delegate);

        defaultLoggerClient.execute(ctx, req);
        verifyNoInteractions(logger);
    }

    @Test
    void sanitizeRequestHeaders() throws Exception {
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/trustin",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "test.com"));

        final ClientRequestContext ctx = clientRequestContext(req);

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isInfoEnabled()).thenReturn(true);

        // Before sanitization
        assertThat(ctx.logBuilder().toString()).contains(":path=/hello/trustin");
        assertThat(ctx.logBuilder().toString()).contains(":authority=test.com");

        final LogFormatter logFormatter = LogFormatter.builderForText()
                                                      .requestHeadersSanitizer(RegexBasedSanitizer.of(
                                                              Pattern.compile("trustin"),
                                                              Pattern.compile("com")))
                                                      .build();

        // use custom logger
        final LogWriter logWriter = LogWriter.builder()
                                             .logger(logger)
                                             .requestLogLevel(LogLevel.INFO)
                                             .successfulResponseLogLevel(LogLevel.INFO)
                                             .logFormatter(logFormatter)
                                             .build();
        final LoggingClient client =
                LoggingClient.builder()
                             .logWriter(logWriter)
                             .build(delegate);

        client.execute(ctx, req);

        // After the sanitization.
        verify(logger, times(2)).isInfoEnabled();

        // verify request log
        verify(logger).info(argThat((String text) -> text.contains("Request:") &&
                                                     !(text.contains(":path=/hello/trustin") ||
                                                       text.contains(":authority=test.com"))));
        // verify response log
        verify(logger).info(matches(".*Response:.*"));
        verifyNoMoreInteractions(logger);
    }

    @Test
    void sanitizeRequestHeadersByLogFormatter() throws Exception {
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/trustin",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "test.com"));

        final ClientRequestContext ctx = clientRequestContext(req);

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isInfoEnabled()).thenReturn(true);

        // Before sanitization
        assertThat(ctx.logBuilder().toString()).contains(":path=/hello/trustin");
        assertThat(ctx.logBuilder().toString()).contains(":authority=test.com");

        final LogFormatter logFormatter =
                LogFormatter.builderForText()
                            .requestHeadersSanitizer(RegexBasedSanitizer.of(Pattern.compile("trustin"),
                                                                            Pattern.compile("com")))
                            .build();
        final LoggingClient client =
                LoggingClient.builder()
                             .logWriter(LogWriter.builder()
                                                 .logger(logger)
                                                 .requestLogLevel(LogLevel.INFO)
                                                 .successfulResponseLogLevel(LogLevel.INFO)
                                                 .logFormatter(logFormatter)
                                                 .build())
                             .build(delegate);

        client.execute(ctx, req);

        // After the sanitization.
        verify(logger, times(2)).isInfoEnabled();

        // verify request log
        verify(logger).info(argThat((String text) -> text.contains("Request:") &&
                                                     !(text.contains(":path=/hello/trustin") ||
                                                       text.contains(":authority=test.com"))));
        // verify response log
        verify(logger).info(matches(".*Response:.*"));
        verifyNoMoreInteractions(logger);
    }

    @Test
    void sanitizeRequestContent() throws Exception {
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/trustin",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "test.com"));

        final ClientRequestContext ctx = clientRequestContext(req);
        ctx.logBuilder().requestContent("Virginia 333-490-4499", "Virginia 333-490-4499");

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isInfoEnabled()).thenReturn(true);

        // Before sanitization
        assertThat(ctx.logBuilder().toString()).contains("333-490-4499");

        final LogFormatter logFormatter =
                LogFormatter.builderForText()
                            .requestContentSanitizer(RegexBasedSanitizer.of(
                                    Pattern.compile("\\d{3}[-.\\s]\\d{3}[-.\\s]\\d{4}")))
                            .build();
        final LoggingClient client =
                LoggingClient.builder()
                             .logWriter(LogWriter.builder()
                                                 .logger(logger)
                                                 .requestLogLevel(LogLevel.INFO)
                                                 .successfulResponseLogLevel(LogLevel.INFO)
                                                 .logFormatter(logFormatter)
                                                 .build())
                             .build(delegate);

        client.execute(ctx, req);

        // Ensure the request content (the phone number 333-490-4499) is sanitized.
        verify(logger, times(2)).isInfoEnabled();

        // verify request log
        verify(logger).info(argThat((String text) -> text.contains("Request:") &&
                                                     !text.contains("333-490-4499")));
        // verify response log
        verify(logger).info(matches(".*Response:.*"));
        verifyNoMoreInteractions(logger);
    }

    @Test
    void sanitizeRequestContentByLogFormatter() throws Exception {
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/trustin",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "test.com"));

        final ClientRequestContext ctx = clientRequestContext(req);
        ctx.logBuilder().requestContent("Virginia 333-490-4499", "Virginia 333-490-4499");

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isInfoEnabled()).thenReturn(true);

        // Before sanitization
        assertThat(ctx.logBuilder().toString()).contains("333-490-4499");

        final LogFormatter logFormatter =
                LogFormatter.builderForText()
                            .requestContentSanitizer(RegexBasedSanitizer.of(
                                    Pattern.compile(
                                            "\\d{3}[-.\\s]\\d{3}[-.\\s]\\d{4}")))
                            .build();
        final LoggingClient client =
                LoggingClient.builder()
                             .logWriter(LogWriter.builder()
                                                 .logger(logger)
                                                 .requestLogLevel(LogLevel.INFO)
                                                 .successfulResponseLogLevel(LogLevel.INFO)
                                                 .logFormatter(logFormatter)
                                                 .build())
                             .build(delegate);

        client.execute(ctx, req);

        // Ensure the request content (the phone number 333-490-4499) is sanitized.
        verify(logger, times(2)).isInfoEnabled();

        // verify request log
        verify(logger).info(argThat((String text) -> text.contains("Request:") &&
                                                     !text.contains("333-490-4499")));
        // verify response log
        verify(logger).info(matches(".*Response:.*"));
        verifyNoMoreInteractions(logger);
    }

    @Test
    void internalServerError() throws Exception {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = clientRequestContext(req);
        ctx.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isDebugEnabled()).thenReturn(true);

        // use custom logger
        final LoggingClient customLoggerClient =
                LoggingClient.builder()
                             .logWriter(LogWriter.of(logger))
                             .build(delegate);

        customLoggerClient.execute(ctx, req);

        verify(logger, times(2)).isDebugEnabled();

        // verify request log
        verify(logger).debug(argThat((String actLog) -> actLog.contains("Request:") &&
                                                        actLog.endsWith("headers=[:method=GET, :path=/]}")));

        // verify response log
        verify(logger).debug(argThat((String actLog) -> actLog.contains("Response:") &&
                                                        actLog.endsWith("headers=[:status=500]}")));
    }

    @Test
    void defaultsError() throws Exception {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = clientRequestContext(req);
        final IllegalStateException cause = new IllegalStateException("Failed");
        ctx.logBuilder().endResponse(cause);

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isWarnEnabled()).thenReturn(true);
        when(logger.isDebugEnabled()).thenReturn(true);

        // use custom logger
        final LoggingClient customLoggerClient =
                LoggingClient.builder()
                             .logWriter(LogWriter.of(logger))
                             .build(delegate);

        customLoggerClient.execute(ctx, req);

        verify(logger, times(2)).isDebugEnabled();
        verify(logger, times(1)).isWarnEnabled();

        // verify request log
        verify(logger).debug(argThat((String actLog) -> actLog.contains("Request:") &&
                                                        actLog.endsWith("headers=[:method=GET, :path=/]}")));

        // verify response log
        verify(logger).warn(argThat((String actLog) -> actLog.contains("Response:") &&
                                                       actLog.endsWith("headers=[:status=0]}")),
                            same(cause));
    }

    @Test
    void shouldLogFailedResponseWhenFailureSamplingRateIsAlways() throws Exception {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = clientRequestContext(req);
        final IllegalStateException cause = new IllegalStateException("Failed");
        ctx.logBuilder().endResponse(cause);

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isDebugEnabled()).thenReturn(false);
        when(logger.isWarnEnabled()).thenReturn(true);

        // use custom logger
        final LoggingClient customLoggerClient =
                LoggingClient.builder()
                             .logWriter(LogWriter.of(logger))
                             .successSamplingRate(0.0f)
                             .build(delegate);

        customLoggerClient.execute(ctx, req);

        verify(logger, times(1)).isWarnEnabled();

        // verify request log
        verify(logger).warn(argThat((String actLog) -> actLog.contains("Request:") &&
                                                       actLog.endsWith("headers=[:method=GET, :path=/]}")));

        // verify response log
        verify(logger).warn(argThat((String actLog) -> actLog.contains("Response:") &&
                                                       actLog.endsWith("headers=[:status=0]}")),
                            same(cause));
    }

    @Test
    void shouldNotLogFailedResponseWhenSamplingRateIsZero() throws Exception {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = clientRequestContext(req);
        final IllegalStateException cause = new IllegalStateException("Failed");
        ctx.logBuilder().endResponse(cause);

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);

        // use custom logger
        final LoggingClient customLoggerClient =
                LoggingClient.builder()
                             .logWriter(LogWriter.of(logger))
                             .samplingRate(0.0f)
                             .build(delegate);

        customLoggerClient.execute(ctx, req);

        verifyNoInteractions(logger);
    }

    @Test
    void sanitizerAndLogWriterCanNotSetTogether() {
        assertThatThrownBy(() -> LoggingClient
                .builder()
                .requestHeadersSanitizer(RegexBasedSanitizer.of(
                        Pattern.compile("trustin"),
                        Pattern.compile("com")))
                .logWriter(LogWriter.of())
                .build(delegate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "The logWriter and the log properties cannot be set together.");
    }
}
