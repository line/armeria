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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.internal.common.logging.LoggingTestUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

class LoggingServiceTest {

    private static final String REQUEST_FORMAT = "{} Request: {}";
    private static final String RESPONSE_FORMAT = "{} Response: {}";
    private static final String RESPONSE_FORMAT2 = "{} Response: {}, cause: {}";

    private static final HttpService delegate = (ctx, req) -> {
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse();
        return HttpResponse.of(200);
    };

    private final AtomicReference<Throwable> capturedCause = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        LoggingTestUtil.throwIfCaptured(capturedCause);
    }

    @Test
    void defaultsSuccess() throws Exception {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        final LoggingService service =
                LoggingService.builder()
                              .logger(logger)
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());
        verify(logger, times(2)).isTraceEnabled();
    }

    @Test
    void defaultsError() throws Exception {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final IllegalStateException cause = new IllegalStateException("Failed");
        ctx.logBuilder().endResponse(cause);

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isWarnEnabled()).thenReturn(true);

        final LoggingService service =
                LoggingService.builder()
                              .logger(logger)
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());

        verify(logger, times(2)).isTraceEnabled();
        verify(logger).isWarnEnabled();
        verify(logger).warn(eq(REQUEST_FORMAT), same(ctx),
                            matches(".*headers=\\[:method=GET, :path=/].*"));
        verify(logger).warn(eq(RESPONSE_FORMAT), same(ctx),
                            matches(".*cause=java\\.lang\\.IllegalStateException: Failed.*"),
                            same(cause));
    }

    @Test
    void infoLevel() throws Exception {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx.logBuilder().responseHeaders(ResponseHeaders.of(200));

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isInfoEnabled()).thenReturn(true);

        final LoggingService service =
                LoggingService.builder()
                              .logger(logger)
                              .requestLogLevel(LogLevel.INFO)
                              .successfulResponseLogLevel(LogLevel.INFO)
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());

        verify(logger).info(eq(REQUEST_FORMAT), same(ctx),
                            matches(".*headers=\\[:method=GET, :path=/].*"));
        verify(logger).info(eq(RESPONSE_FORMAT), same(ctx),
                            matches(".*headers=\\[:status=200].*"));
    }

    @Test
    void mapRequestLogLevelMapper() throws Exception {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(RequestHeaders.of(
                HttpMethod.GET, "/", "x-req", "test", "x-res", "test")));
        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isWarnEnabled()).thenReturn(true);

        final LoggingService service =
                LoggingService.builder()
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
                              .newDecorator().apply(delegate);

        // Check if logs at WARN level if there are headers we're looking for.
        service.serve(ctx, ctx.request());
        verify(logger, never()).isInfoEnabled();
        verify(logger, times(2)).isWarnEnabled();
        verify(logger).warn(eq(REQUEST_FORMAT), same(ctx),
                            matches(".*headers=\\[:method=GET, :path=/, x-req=test, x-res=test].*"));
        verify(logger).warn(eq(RESPONSE_FORMAT), same(ctx), anyString());
    }

    @Test
    void mapRequestLogLevelMapperUnmatched() throws Exception {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isInfoEnabled()).thenReturn(true);

        final LoggingService service =
                LoggingService.builder()
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
                              .newDecorator().apply(delegate);

        // Check if logs at INFO level if there are no headers we're looking for.
        service.serve(ctx, ctx.request());
        verify(logger, times(2)).isInfoEnabled();
        verify(logger, never()).isWarnEnabled();
        verify(logger).info(eq(REQUEST_FORMAT), same(ctx),
                            matches(".*headers=\\[:method=GET, :path=/].*"));
        verify(logger).info(eq(RESPONSE_FORMAT), same(ctx), anyString());
        verifyNoMoreInteractions(logger);
    }

    @Test
    void duplicateSetRequestLogLevelAndMapper() throws Exception {
        assertThatThrownBy(() -> LoggingService.builder()
                                               .requestLogLevel(LogLevel.INFO)
                                               .requestLogLevelMapper(log -> LogLevel.INFO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reversedDuplicateSetRequestLogLevelAndMapper() throws Exception {
        assertThatThrownBy(() -> LoggingService.builder()
                                               .requestLogLevelMapper(log -> LogLevel.INFO)
                                               .requestLogLevel(LogLevel.INFO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateSetSuccessfulResponseLogLevelAndMapper() throws Exception {
        assertThatThrownBy(() -> LoggingService.builder()
                                               .successfulResponseLogLevel(LogLevel.INFO)
                                               .responseLogLevelMapper(log -> LogLevel.INFO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reversedDuplicateSetSuccessfulResponseLogLevelAndMapper() throws Exception {
        assertThatThrownBy(() -> LoggingService.builder()
                                               .responseLogLevelMapper(log -> LogLevel.INFO)
                                               .successfulResponseLogLevel(LogLevel.INFO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateSetFailureResponseLogLevelAndMapper() throws Exception {
        assertThatThrownBy(() -> LoggingService.builder()
                                               .failureResponseLogLevel(LogLevel.INFO)
                                               .responseLogLevelMapper(log -> LogLevel.INFO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reversedDuplicateSetFailureResponseLogLevelAndMapper() throws Exception {
        assertThatThrownBy(() -> LoggingService.builder()
                                               .responseLogLevelMapper(log -> LogLevel.INFO)
                                               .failureResponseLogLevel(LogLevel.INFO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sanitize() throws Exception {
        final String sanitizedRequestHeaders = "sanitizedRequestHeaders";
        final String sanitizedRequestContent = "sanitizedRequestContent";
        final String sanitizedRequestTrailers = "sanitizedRequestTrailer";
        final String sanitizedResponseHeaders = "sanitizedResponseHeaders";
        final String sanitizedResponseContent = "sanitizedResponseContent";
        final String sanitizedResponseTrailers = "sanitizedResponseTrailer";
        final Function<HttpHeaders, ?> requestHeadersSanitizer = headers -> sanitizedRequestHeaders;
        final Function<Object, ?> requestContentSanitizer = content -> sanitizedRequestContent;
        final Function<HttpHeaders, ?> requestTrailersSanitizer = trailers -> sanitizedRequestTrailers;
        final Function<HttpHeaders, ?> responseHeadersSanitizer = headers -> sanitizedResponseHeaders;
        final Function<Object, ?> responseContentSanitizer = content -> sanitizedResponseContent;
        final Function<HttpHeaders, ?> responseTrailersSanitizer = trailers -> sanitizedResponseTrailers;

        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx.logBuilder().requestContent(new Object(), new Object());
        ctx.logBuilder().requestTrailers(HttpHeaders.of("foo", "bar"));
        ctx.logBuilder().responseHeaders(ResponseHeaders.of(200));
        ctx.logBuilder().responseContent(new Object(), new Object());
        ctx.logBuilder().responseTrailers(HttpHeaders.of("foo", "bar"));

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isInfoEnabled()).thenReturn(true);

        final LoggingService service =
                LoggingService.builder()
                              .logger(logger)
                              .requestLogLevel(LogLevel.INFO)
                              .successfulResponseLogLevel(LogLevel.INFO)
                              .requestHeadersSanitizer(requestHeadersSanitizer)
                              .requestContentSanitizer(requestContentSanitizer)
                              .requestTrailersSanitizer(requestTrailersSanitizer)
                              .requestTrailersSanitizer(requestTrailersSanitizer)
                              .responseHeadersSanitizer(responseHeadersSanitizer)
                              .responseContentSanitizer(responseContentSanitizer)
                              .responseTrailersSanitizer(responseTrailersSanitizer)
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());

        verify(logger, times(2)).isInfoEnabled();
        verify(logger).info(eq(REQUEST_FORMAT), same(ctx),
                            matches(".*" + sanitizedRequestHeaders + ".*" + sanitizedRequestContent + ".*" +
                                    sanitizedRequestTrailers + ".*"));
        verify(logger).info(eq(RESPONSE_FORMAT), same(ctx),
                            matches(".*" + sanitizedResponseHeaders + ".*" + sanitizedResponseContent + ".*" +
                                    sanitizedResponseTrailers + ".*"));
    }

    @Test
    void sanitizeExceptionIntoException() throws Exception {
        final Exception sanitizedResponseCause = new Exception("sanitized");
        final Function<Throwable, Throwable> responseCauseSanitizer = cause -> sanitizedResponseCause;

        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx.logBuilder().endResponse(new Exception("not sanitized"));

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isInfoEnabled()).thenReturn(true);
        when(logger.isWarnEnabled()).thenReturn(true);

        final LoggingService service =
                LoggingService.builder()
                              .logger(logger)
                              .requestLogLevel(LogLevel.INFO)
                              .successfulResponseLogLevel(LogLevel.INFO)
                              .responseCauseSanitizer(responseCauseSanitizer)
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());
        verify(logger, times(2)).isInfoEnabled();
        verify(logger).info(eq(REQUEST_FORMAT), same(ctx), anyString());
        verify(logger, times(1)).isWarnEnabled();
        verify(logger).warn(eq(RESPONSE_FORMAT), same(ctx), anyString(),
                            same(sanitizedResponseCause));
    }

    @Test
    void sanitizeExceptionIntoString() throws Exception {
        final String sanitizedResponseCause = "sanitizedResponseCause";
        final Function<Throwable, String> responseCauseSanitizer = cause -> sanitizedResponseCause;

        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx.logBuilder().endResponse(new Exception("not sanitized"));

        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        when(logger.isInfoEnabled()).thenReturn(true);
        when(logger.isWarnEnabled()).thenReturn(true);

        final LoggingService service =
                LoggingService.builder()
                              .logger(logger)
                              .requestLogLevel(LogLevel.INFO)
                              .successfulResponseLogLevel(LogLevel.INFO)
                              .responseCauseSanitizer(responseCauseSanitizer)
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());
        verify(logger, times(2)).isInfoEnabled();
        verify(logger).info(eq(REQUEST_FORMAT), same(ctx), anyString());
        verify(logger, times(1)).isWarnEnabled();
        verify(logger).warn(eq(RESPONSE_FORMAT2), same(ctx), anyString(),
                            same(sanitizedResponseCause));
    }

    @Test
    void sample() throws Exception {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final Logger logger = LoggingTestUtil.newMockLogger(ctx, capturedCause);
        final LoggingService service =
                LoggingService.builder()
                              .logger(logger)
                              .requestLogLevel(LogLevel.INFO)
                              .successfulResponseLogLevel(LogLevel.INFO)
                              .samplingRate(0.0f)
                              .newDecorator().apply(delegate);

        service.serve(ctx, ctx.request());
        verifyNoInteractions(logger);
    }
}
