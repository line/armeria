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
package com.linecorp.armeria.server.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.LogFormatter;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.logging.RegexBasedSanitizer;
import com.linecorp.armeria.internal.common.logging.LoggingTestUtil;
import com.linecorp.armeria.internal.testing.ImmediateEventLoop;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServiceRequestContext;

class LoggingServiceTest {

    static final HttpService delegate = (ctx, req) -> {
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse();
        return HttpResponse.of(200);
    };

    private final AtomicReference<Throwable> capturedCause = new AtomicReference<>();

    private static Stream<Arguments> expectedException() {
        return Stream.of(HttpStatusException.of(500),
                         HttpStatusException.of(500, new IllegalStateException("status")),
                         HttpResponseException.of(HttpResponse.of("OK")),
                         HttpResponseException.of(HttpResponse.of("OK"), new IllegalStateException("body")))
                     .map(Arguments::of);
    }

    @AfterEach
    void tearDown() {
        LoggingTestUtil.throwIfCaptured(capturedCause);
    }

    private static ServiceRequestContext serviceRequestContext() {
        return serviceRequestContext(HttpRequest.of(HttpMethod.GET, "/"));
    }

    private static ServiceRequestContext serviceRequestContext(HttpRequest req) {
        return ServiceRequestContext.builder(req)
                                    .eventLoop(ImmediateEventLoop.INSTANCE)
                                    .build();
    }

    @Test
    void defaultsSuccess() throws Exception {
        final ServiceRequestContext ctx = serviceRequestContext();
        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        final LoggingService service =
                LoggingService.builder()
                              .logWriter(LogWriter.of(logger))
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());
        verify(logger, times(2)).isDebugEnabled();
    }

    @Test
    void defaultsError() throws Exception {
        final ServiceRequestContext ctx = serviceRequestContext();
        final IllegalStateException cause = new IllegalStateException("Failed");
        ctx.logBuilder().endResponse(cause);
        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isWarnEnabled()).thenReturn(true);

        final LoggingService service =
                LoggingService.builder()
                              .logWriter(LogWriter.of(logger))
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());

        verify(logger).isWarnEnabled();
        verify(logger).warn(matches(".*Request:.*headers=\\[:method=GET, :path=/].*"));
        verify(logger).warn(matches(".*Response:.*cause=java\\.lang\\.IllegalStateException: Failed.*"),
                            same(cause));
    }

    @MethodSource("expectedException")
    @ParameterizedTest
    void shouldNotLogHttpStatusAndResponseExceptions(Exception exception) throws Exception {
        final ServiceRequestContext ctx = serviceRequestContext();
        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        final Throwable cause = exception.getCause();
        ctx.logBuilder().endResponse(exception);

        if (cause == null) {
            when(logger.isDebugEnabled()).thenReturn(true);
        } else {
            when(logger.isWarnEnabled()).thenReturn(true);
        }
        final LoggingService service =
                LoggingService.builder()
                              .logWriter(LogWriter.of(logger))
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());

        if (cause == null) {
            // Log a response without an HttpResponseException or HttpStatusException
            verify(logger).debug(matches(".*Request:.*headers=\\[:method=GET, :path=/].*"));
            verify(logger).debug(matches(".*Response:.*"));
        } else {
            verify(logger).warn(matches(".*Request:.*headers=\\[:method=GET, :path=/].*"));
            verify(logger).warn(matches(".*Response:.*cause=" + cause.getClass().getName() + ".*"),
                                same(cause));
        }
    }

    @Test
    void infoLevel() throws Exception {
        final ServiceRequestContext ctx = serviceRequestContext();
        ctx.logBuilder().responseHeaders(ResponseHeaders.of(200));

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isInfoEnabled()).thenReturn(true);

        final LogWriter logWriter = LogWriter.builder()
                                             .logger(logger)
                                             .requestLogLevel(LogLevel.INFO)
                                             .successfulResponseLogLevel(LogLevel.INFO)
                                             .build();
        final LoggingService service =
                LoggingService.builder()
                              .logWriter(logWriter)
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());

        verify(logger).info(matches(".*Request:.*headers=\\[:method=GET, :path=/].*"));
        verify(logger).info(matches(".*Response:.*headers=\\[:status=200].*"));
        verifyNoMoreInteractions(logger);
    }

    @Test
    void mapRequestLogLevelMapper() throws Exception {
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(
                HttpMethod.GET, "/", "x-req", "test", "x-res", "test"));
        final ServiceRequestContext ctx = serviceRequestContext(req);
        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isWarnEnabled()).thenReturn(true);

        final LogWriter logWriter = LogWriter.builder()
                                             .logger(logger)
                                             .requestLogLevelMapper(log -> {
                                                 if (log.requestHeaders().contains("x-req")) {
                                                     return LogLevel.WARN;
                                                 } else {
                                                     return LogLevel.INFO;
                                                 }
                                             })
                                             .responseLogLevelMapper(log -> {
                                                 if (log.requestHeaders().contains("x-res")) {
                                                     return LogLevel.WARN;
                                                 } else {
                                                     return LogLevel.INFO;
                                                 }
                                             })
                                             .build();
        final LoggingService service =
                LoggingService.builder()
                              .logWriter(logWriter)
                              .newDecorator().apply(delegate);

        // Check if logs at WARN level if there are headers we're looking for.
        service.serve(ctx, ctx.request());
        verify(logger, never()).isInfoEnabled();
        verify(logger, times(2)).isWarnEnabled();
        verify(logger).warn(matches(".*Request:.*headers=\\[:method=GET, :path=/, x-req=test, x-res=test].*"));
        verify(logger).warn(matches(".*Response:.*"));
        verifyNoMoreInteractions(logger);
    }

    @Test
    void mapRequestLogLevelMapperUnmatched() throws Exception {
        final ServiceRequestContext ctx = serviceRequestContext();
        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isInfoEnabled()).thenReturn(true);

        final LogWriter logWriter = LogWriter.builder()
                                             .logger(logger)
                                             .requestLogLevelMapper(log -> {
                                                 if (log.requestHeaders().contains("x-req")) {
                                                     return LogLevel.WARN;
                                                 } else {
                                                     return LogLevel.INFO;
                                                 }
                                             })
                                             .responseLogLevelMapper(log -> {
                                                 if (log.requestHeaders().contains("x-res")) {
                                                     return LogLevel.WARN;
                                                 } else {
                                                     return LogLevel.INFO;
                                                 }
                                             })
                                             .build();
        final LoggingService service =
                LoggingService.builder()
                              .logWriter(logWriter)
                              .newDecorator().apply(delegate);

        // Check if logs at INFO level if there are no headers we're looking for.
        service.serve(ctx, ctx.request());
        verify(logger, times(2)).isInfoEnabled();
        verify(logger, never()).isWarnEnabled();
        verify(logger).info(matches(".*Request:.*headers=\\[:method=GET, :path=/].*"));
        verify(logger).info(matches(".*Response:.*"));
        verifyNoMoreInteractions(logger);
    }

    @Test
    void sanitize() throws Exception {
        final String sanitizedRequestHeaders = "sanitizedRequestHeaders";
        final String sanitizedRequestContent = "sanitizedRequestContent";
        final String sanitizedRequestTrailers = "sanitizedRequestTrailer";
        final String sanitizedResponseHeaders = "sanitizedResponseHeaders";
        final String sanitizedResponseContent = "sanitizedResponseContent";
        final String sanitizedResponseTrailers = "sanitizedResponseTrailer";
        final BiFunction<RequestContext, HttpHeaders, String> requestHeadersSanitizer =
                (ctx, headers) -> sanitizedRequestHeaders;
        final BiFunction<RequestContext, Object, String> requestContentSanitizer =
                (ctx, content) -> sanitizedRequestContent;
        final BiFunction<RequestContext, HttpHeaders, String> requestTrailersSanitizer =
                (ctx, trailers) -> sanitizedRequestTrailers;
        final BiFunction<RequestContext, HttpHeaders, String> responseHeadersSanitizer =
                (ctx, headers) -> sanitizedResponseHeaders;
        final BiFunction<RequestContext, Object, String> responseContentSanitizer =
                (ctx, content) -> sanitizedResponseContent;
        final BiFunction<RequestContext, HttpHeaders, String> responseTrailersSanitizer =
                (ctx, trailers) -> sanitizedResponseTrailers;

        final ServiceRequestContext ctx = serviceRequestContext();
        ctx.logBuilder().requestContent(new Object(), new Object());
        ctx.logBuilder().requestTrailers(HttpHeaders.of("foo", "bar"));
        ctx.logBuilder().responseHeaders(ResponseHeaders.of(200));
        ctx.logBuilder().responseContent(new Object(), new Object());
        ctx.logBuilder().responseTrailers(HttpHeaders.of("foo", "bar"));

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isInfoEnabled()).thenReturn(true);

        final LogFormatter logFormatter = LogFormatter.builderForText()
                                                      .requestHeadersSanitizer(requestHeadersSanitizer)
                                                      .requestContentSanitizer(requestContentSanitizer)
                                                      .requestTrailersSanitizer(requestTrailersSanitizer)
                                                      .requestTrailersSanitizer(requestTrailersSanitizer)
                                                      .responseHeadersSanitizer(responseHeadersSanitizer)
                                                      .responseContentSanitizer(responseContentSanitizer)
                                                      .responseTrailersSanitizer(responseTrailersSanitizer)
                                                      .build();
        final LogWriter logWriter = LogWriter.builder()
                                             .logger(logger)
                                             .requestLogLevel(LogLevel.INFO)
                                             .successfulResponseLogLevel(LogLevel.INFO)
                                             .logFormatter(logFormatter)
                                             .build();
        final LoggingService service =
                LoggingService.builder()
                              .logWriter(logWriter)
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());

        verify(logger, times(2)).isInfoEnabled();
        verify(logger).info(matches(".*Request:.*" + sanitizedRequestHeaders + ".*" + sanitizedRequestContent +
                                    ".*" + sanitizedRequestTrailers + ".*"));
        verify(logger).info(matches(".*Response:.*" + sanitizedResponseHeaders + ".*" +
                                    sanitizedResponseContent + ".*" + sanitizedResponseTrailers + ".*"));
    }

    @Test
    void sanitizeByLogFormatter() throws Exception {
        final String sanitizedRequestHeaders = "sanitizedRequestHeaders";
        final String sanitizedRequestContent = "sanitizedRequestContent";
        final String sanitizedRequestTrailers = "sanitizedRequestTrailer";
        final String sanitizedResponseHeaders = "sanitizedResponseHeaders";
        final String sanitizedResponseContent = "sanitizedResponseContent";
        final String sanitizedResponseTrailers = "sanitizedResponseTrailer";
        final BiFunction<RequestContext, HttpHeaders, String> requestHeadersSanitizer =
                (ctx, headers) -> sanitizedRequestHeaders;
        final BiFunction<RequestContext, Object, String> requestContentSanitizer =
                (ctx, content) -> sanitizedRequestContent;
        final BiFunction<RequestContext, HttpHeaders, String> requestTrailersSanitizer =
                (ctx, trailers) -> sanitizedRequestTrailers;
        final BiFunction<RequestContext, HttpHeaders, String> responseHeadersSanitizer =
                (ctx, headers) -> sanitizedResponseHeaders;
        final BiFunction<RequestContext, Object, String> responseContentSanitizer =
                (ctx, content) -> sanitizedResponseContent;
        final BiFunction<RequestContext, HttpHeaders, String> responseTrailersSanitizer =
                (ctx, trailers) -> sanitizedResponseTrailers;

        final ServiceRequestContext ctx = serviceRequestContext();
        ctx.logBuilder().requestContent(new Object(), new Object());
        ctx.logBuilder().requestTrailers(HttpHeaders.of("foo", "bar"));
        ctx.logBuilder().responseHeaders(ResponseHeaders.of(200));
        ctx.logBuilder().responseContent(new Object(), new Object());
        ctx.logBuilder().responseTrailers(HttpHeaders.of("foo", "bar"));

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isInfoEnabled()).thenReturn(true);

        final LogFormatter logFormatter = LogFormatter.builderForText()
                                                      .requestHeadersSanitizer(requestHeadersSanitizer)
                                                      .requestContentSanitizer(requestContentSanitizer)
                                                      .requestTrailersSanitizer(requestTrailersSanitizer)
                                                      .requestTrailersSanitizer(requestTrailersSanitizer)
                                                      .responseHeadersSanitizer(responseHeadersSanitizer)
                                                      .responseContentSanitizer(responseContentSanitizer)
                                                      .responseTrailersSanitizer(responseTrailersSanitizer)
                                                      .build();
        final LoggingService service =
                LoggingService.builder()
                              .logWriter(LogWriter.builder()
                                                  .logger(logger)
                                                  .requestLogLevel(LogLevel.INFO)
                                                  .successfulResponseLogLevel(LogLevel.INFO)
                                                  .logFormatter(logFormatter)
                                                  .build())
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());

        verify(logger, times(2)).isInfoEnabled();
        verify(logger).info(matches(".*Request:.*" + sanitizedRequestHeaders + ".*" + sanitizedRequestContent +
                                    ".*" + sanitizedRequestTrailers + ".*"));
        verify(logger).info(matches(".*Response:.*" + sanitizedResponseHeaders + ".*" +
                                    sanitizedResponseContent + ".*" + sanitizedResponseTrailers + ".*"));
    }

    @Test
    void sanitizeRequestHeaders() throws Exception {

        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/trustin",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "test.com"));

        final ServiceRequestContext ctx = serviceRequestContext(req);
        final Exception cause = new Exception("not sanitized");
        ctx.logBuilder().endResponse(cause);

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isInfoEnabled()).thenReturn(true);
        when(logger.isWarnEnabled()).thenReturn(true);

        // Before sanitization
        assertThat(ctx.logBuilder().toString()).contains(":path=/hello/trustin");
        assertThat(ctx.logBuilder().toString()).contains(":authority=test.com");

        final LogFormatter logFormatter =
                LogFormatter.builderForText()
                            .requestHeadersSanitizer(RegexBasedSanitizer.of(Pattern.compile("trustin"),
                                                                            Pattern.compile("com")))
                            .build();
        final LoggingService service =
                LoggingService.builder()
                              .logWriter(LogWriter.builder()
                                                  .logger(logger)
                                                  .requestLogLevel(LogLevel.INFO)
                                                  .successfulResponseLogLevel(LogLevel.INFO)
                                                  .logFormatter(logFormatter)
                                                  .build())
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());

        // After the sanitization.
        verify(logger, times(2)).isInfoEnabled();
        verify(logger, times(1)).isWarnEnabled();

        // verify request logs
        for (int i = 0; i < 2; i++) {
            verify(logger).info(argThat((String text) -> text.contains("Request:") &&
                                                         !(text.contains(":path=/hello/trustin") ||
                                                           text.contains(":authority=test.com"))));
        }

        // verify response log
        verify(logger).warn(matches(".*Response:.*"), eq(cause));
        verifyNoMoreInteractions(logger);
    }

    @Test
    void sanitizeRequestHeadersByLogFormatter() throws Exception {

        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/trustin",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "test.com"));

        final ServiceRequestContext ctx = serviceRequestContext(req);
        final Exception cause = new Exception("not sanitized");
        ctx.logBuilder().endResponse(cause);

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isInfoEnabled()).thenReturn(true);
        when(logger.isWarnEnabled()).thenReturn(true);

        // Before sanitization
        assertThat(ctx.logBuilder().toString()).contains(":path=/hello/trustin");
        assertThat(ctx.logBuilder().toString()).contains(":authority=test.com");

        final LogFormatter logFormatter =
                LogFormatter.builderForText()
                            .requestHeadersSanitizer(RegexBasedSanitizer.of(Pattern.compile("trustin"),
                                                                            Pattern.compile("com")))
                            .build();
        final LoggingService service =
                LoggingService.builder()
                              .logWriter(LogWriter.builder()
                                                  .logger(logger)
                                                  .requestLogLevel(LogLevel.INFO)
                                                  .successfulResponseLogLevel(LogLevel.INFO)
                                                  .logFormatter(logFormatter)
                                                  .build())
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());

        // After the sanitization.
        verify(logger, times(2)).isInfoEnabled();
        verify(logger, times(1)).isWarnEnabled();

        // verify request logs
        for (int i = 0; i < 2; i++) {
            verify(logger).info(argThat((String text) -> text.contains("Request:") &&
                                                         !(text.contains(":path=/hello/trustin") ||
                                                           text.contains(":authority=test.com"))));
        }

        // verify response log
        verify(logger).warn(matches(".*Response:.*"), eq(cause));
        verifyNoMoreInteractions(logger);
    }

    @Test
    void sanitizeRequestContent() throws Exception {

        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/trustin",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "test.com"));

        final ServiceRequestContext ctx = serviceRequestContext(req);
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
        final LogWriter logWriter = LogWriter.builder()
                                             .logger(logger)
                                             .requestLogLevel(LogLevel.INFO)
                                             .successfulResponseLogLevel(LogLevel.INFO)
                                             .logFormatter(logFormatter)
                                             .build();
        final LoggingService service =
                LoggingService.builder()
                              .logWriter(logWriter)
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());

        // Ensure the request content (the phone number 333-490-4499) is sanitized.
        verify(logger, times(2)).isInfoEnabled();

        // verify request and response log
        verify(logger, times(2)).info(argThat((String text) -> !text.contains("333-490-4499")));
        verifyNoMoreInteractions(logger);
    }

    @Test
    void sanitizeRequestContentByLogFormatter() throws Exception {

        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/trustin",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "test.com"));

        final ServiceRequestContext ctx = serviceRequestContext(req);
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
        final LoggingService service =
                LoggingService.builder()
                              .logWriter(LogWriter.builder()
                                                  .logger(logger)
                                                  .requestLogLevel(LogLevel.INFO)
                                                  .successfulResponseLogLevel(LogLevel.INFO)
                                                  .logFormatter(logFormatter)
                                                  .build())
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());

        // Ensure the request content (the phone number 333-490-4499) is sanitized.
        verify(logger, times(2)).isInfoEnabled();

        // verify request and response log
        verify(logger, times(2)).info(argThat((String text) -> !text.contains("333-490-4499")));
        verifyNoMoreInteractions(logger);
    }

    @Test
    void sample() throws Exception {
        final ServiceRequestContext ctx = serviceRequestContext();
        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        final LogWriter logWriter = LogWriter.builder()
                                             .logger(logger)
                                             .requestLogLevel(LogLevel.INFO)
                                             .successfulResponseLogLevel(LogLevel.INFO)
                                             .build();
        final LoggingService service =
                LoggingService.builder()
                              .logWriter(logWriter)
                              .samplingRate(0.0f)
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());
        verifyNoMoreInteractions(logger);
    }

    @Test
    void shouldLogFailedRequestWhenFailureSamplingRateIsAlways() throws Exception {
        final ServiceRequestContext ctx = serviceRequestContext();
        final IllegalStateException cause = new IllegalStateException("Failed");
        ctx.logBuilder().endResponse(cause);
        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isDebugEnabled()).thenReturn(false);
        when(logger.isWarnEnabled()).thenReturn(true);

        final LoggingService service =
                LoggingService.builder()
                              .logWriter(LogWriter.of(logger))
                              .successSamplingRate(0.0f)
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());
        verify(logger).isWarnEnabled();
        verify(logger).warn(matches(".*Request:.*headers=\\[:method=GET, :path=/].*"));
        verify(logger).warn(matches(".* Response: .*cause=java\\.lang\\.IllegalStateException: Failed.*"),
                            same(cause));
        verifyNoMoreInteractions(logger);
    }

    @Test
    void shouldNotLogFailedRequestWhenSamplingRateIsZero() throws Exception {
        final ServiceRequestContext ctx = serviceRequestContext();
        final IllegalStateException cause = new IllegalStateException("Failed");
        ctx.logBuilder().endResponse(cause);
        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);

        final LoggingService service =
                LoggingService.builder()
                              .logWriter(LogWriter.of(logger))
                              .samplingRate(0.0f)
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());
        verifyNoInteractions(logger);
    }

    @Test
    void responseCauseFilter() throws Exception {
        final ServiceRequestContext ctx = serviceRequestContext();
        final IllegalStateException cause = new IllegalStateException("Failed");
        ctx.logBuilder().endResponse(cause);
        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isWarnEnabled()).thenReturn(true);

        final LoggingService service =
                LoggingService.builder()
                              .logWriter(LogWriter.builder()
                                                  .logger(logger)
                                                  .responseCauseFilter(throwable -> true)
                                                  .build())
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());

        verify(logger).isWarnEnabled();
        verify(logger).warn(matches(".*Request:.*headers=\\[:method=GET, :path=/].*"));
        verify(logger).warn(argThat((String actLog) -> actLog.endsWith("headers=[:status=0]}")));
    }

    @Test
    void sanitizerAndLogWriterCanNotSetTogether() {
        assertThatThrownBy(() -> LoggingService
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
