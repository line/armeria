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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.slf4j.Logger;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

public class LoggingServiceTest {

    private static final HttpRequest REQUEST = HttpRequest.of(HttpMethod.GET, "/foo");

    private static final HttpHeaders REQUEST_HEADERS = HttpHeaders.of(HttpHeaderNames.COOKIE, "armeria");
    private static final Object REQUEST_CONTENT = "request with pii";
    private static final HttpHeaders REQUEST_TRAILERS = HttpHeaders.of(HttpHeaderNames.CONTENT_MD5,
                                                                       "barmeria");

    private static final HttpHeaders RESPONSE_HEADERS = HttpHeaders.of(HttpHeaderNames.SET_COOKIE, "carmeria");
    private static final Object RESPONSE_CONTENT = "response with pii";
    private static final HttpHeaders RESPONSE_TRAILERS = HttpHeaders.of(HttpHeaderNames.CONTENT_MD5,
                                                                        "darmeria");

    private static final String REQUEST_FORMAT = "{} Request: {}";
    private static final String RESPONSE_FORMAT = "{} Response: {}";

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private ServiceRequestContext ctx;

    @Mock
    private Logger logger;

    @Mock
    private RequestLog log;

    @Mock
    private HttpService delegate;

    @Before
    public void setUp() {
        // Logger only logs INFO + WARN.
        when(logger.isInfoEnabled()).thenReturn(true);
        when(logger.isWarnEnabled()).thenReturn(true);

        when(ctx.log()).thenReturn(log);
        when(log.whenRequestComplete()).thenReturn(CompletableFuture.completedFuture(log));
        when(log.whenComplete()).thenReturn(CompletableFuture.completedFuture(log));

        when(log.toStringRequestOnly(any(), any(), any())).thenAnswer(invocation -> {
            final Function<HttpHeaders, HttpHeaders> headersSanitizer = invocation.getArgument(0);
            final Function<Object, Object> contentSanitizer = invocation.getArgument(1);
            final Function<HttpHeaders, HttpHeaders> trailersSanitizer = invocation.getArgument(2);
            return "headers: " + headersSanitizer.apply(REQUEST_HEADERS) +
                   ", content: " + contentSanitizer.apply(REQUEST_CONTENT) +
                   ", trailers: " + trailersSanitizer.apply(REQUEST_TRAILERS);
        });
        when(log.toStringResponseOnly(any(), any(), any())).thenAnswer(invocation -> {
            final Function<HttpHeaders, HttpHeaders> headersSanitizer = invocation.getArgument(0);
            final Function<Object, Object> contentSanitizer = invocation.getArgument(1);
            final Function<HttpHeaders, HttpHeaders> trailersSanitizer = invocation.getArgument(2);
            return "headers: " + headersSanitizer.apply(RESPONSE_HEADERS) +
                   ", content: " + contentSanitizer.apply(RESPONSE_CONTENT) +
                   ", trailers: " + trailersSanitizer.apply(RESPONSE_TRAILERS);
        });
        when(log.context()).thenReturn(ctx);
    }

    @Test
    public void defaults_success() throws Exception {
        final LoggingService service =
                LoggingService.builder()
                              .logger(logger)
                              .newDecorator().apply(delegate);
        service.serve(ctx, REQUEST);
        verify(logger, times(2)).isTraceEnabled();
        verifyNoMoreInteractions(logger);
    }

    @Test
    public void defaults_error() throws Exception {
        final LoggingService service =
                LoggingService.builder()
                              .logger(logger)
                              .newDecorator().apply(delegate);
        final IllegalStateException cause = new IllegalStateException("Failed");
        when(log.responseCause()).thenReturn(cause);
        service.serve(ctx, REQUEST);
        verify(logger, times(2)).isTraceEnabled();
        verify(logger, times(1)).isWarnEnabled();
        verify(logger).warn(REQUEST_FORMAT, ctx,
                            "headers: " + REQUEST_HEADERS + ", content: " + REQUEST_CONTENT +
                            ", trailers: " + REQUEST_TRAILERS);
        verify(logger).warn(RESPONSE_FORMAT, ctx,
                            "headers: " + RESPONSE_HEADERS + ", content: " + RESPONSE_CONTENT +
                            ", trailers: " + RESPONSE_TRAILERS,
                            cause);
        verifyNoMoreInteractions(logger);
    }

    @Test
    public void infoLevel() throws Exception {
        final LoggingService service =
                LoggingService.builder()
                              .logger(logger)
                              .requestLogLevel(LogLevel.INFO)
                              .successfulResponseLogLevel(LogLevel.INFO)
                              .newDecorator().apply(delegate);
        service.serve(ctx, REQUEST);
        verify(logger).info(REQUEST_FORMAT, ctx,
                            "headers: " + REQUEST_HEADERS + ", content: " + REQUEST_CONTENT +
                            ", trailers: " + REQUEST_TRAILERS);
        verify(logger).info(RESPONSE_FORMAT, ctx,
                            "headers: " + RESPONSE_HEADERS + ", content: " + RESPONSE_CONTENT +
                            ", trailers: " + RESPONSE_TRAILERS);
    }

    @Test
    public void mapRequestLogLevelMapper() throws Exception {
        when(log.requestHeaders()).thenAnswer(invocation -> RequestHeaders.of(HttpMethod.GET, "/",
                                                                              "x-req", "test",
                                                                              "x-res", "test"));

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

        service.serve(ctx, REQUEST);
        verify(logger, times(2)).isWarnEnabled();
        verify(logger).warn(REQUEST_FORMAT, ctx,
                            "headers: " + REQUEST_HEADERS + ", content: " + REQUEST_CONTENT +
                            ", trailers: " + REQUEST_TRAILERS);
        verify(logger).warn(RESPONSE_FORMAT, ctx,
                            "headers: " + RESPONSE_HEADERS + ", content: " + RESPONSE_CONTENT +
                            ", trailers: " + RESPONSE_TRAILERS);

        clearInvocations(logger);
        when(log.requestHeaders()).thenAnswer(invocation -> RequestHeaders.of(HttpMethod.GET, "/"));

        service.serve(ctx, REQUEST);
        verify(logger, times(2)).isInfoEnabled();
        verify(logger).info(REQUEST_FORMAT, ctx,
                            "headers: " + REQUEST_HEADERS + ", content: " + REQUEST_CONTENT +
                            ", trailers: " + REQUEST_TRAILERS);
        verify(logger).info(RESPONSE_FORMAT, ctx,
                            "headers: " + RESPONSE_HEADERS + ", content: " + RESPONSE_CONTENT +
                            ", trailers: " + RESPONSE_TRAILERS);
        verifyNoMoreInteractions(logger);
    }

    @Test
    public void duplicateSetRequestLogLevelAndMapper() throws Exception {
        assertThatThrownBy(() -> LoggingService.builder()
                                               .requestLogLevel(LogLevel.INFO)
                                               .requestLogLevelMapper(log -> LogLevel.INFO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void reversedDuplicateSetRequestLogLevelAndMapper() throws Exception {
        assertThatThrownBy(() -> LoggingService.builder()
                                               .requestLogLevelMapper(log -> LogLevel.INFO)
                                               .requestLogLevel(LogLevel.INFO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void duplicateSetSuccessfulResponseLogLevelAndMapper() throws Exception {
        assertThatThrownBy(() -> LoggingService.builder()
                                               .successfulResponseLogLevel(LogLevel.INFO)
                                               .responseLogLevelMapper(log -> LogLevel.INFO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void reversedDuplicateSetSuccessfulResponseLogLevelAndMapper() throws Exception {
        assertThatThrownBy(() -> LoggingService.builder()
                                               .responseLogLevelMapper(log -> LogLevel.INFO)
                                               .successfulResponseLogLevel(LogLevel.INFO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void duplicateSetFailureResponseLogLevelAndMapper() throws Exception {
        assertThatThrownBy(() -> LoggingService.builder()
                                               .failureResponseLogLevel(LogLevel.INFO)
                                               .responseLogLevelMapper(log -> LogLevel.INFO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void reversedDuplicateSetFailureResponseLogLevelAndMapper() throws Exception {
        assertThatThrownBy(() -> LoggingService.builder()
                                               .responseLogLevelMapper(log -> LogLevel.INFO)
                                               .failureResponseLogLevel(LogLevel.INFO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void sanitize() throws Exception {
        final HttpHeaders sanitizedRequestHeaders =
                HttpHeaders.of(HttpHeaderNames.CONTENT_TYPE, "no cookies, too bad");
        final Function<HttpHeaders, HttpHeaders> requestHeadersSanitizer = headers -> {
            assertThat(headers).isEqualTo(REQUEST_HEADERS);
            return sanitizedRequestHeaders;
        };
        final Function<Object, Object> requestContentSanitizer = content -> {
            assertThat(content).isEqualTo(REQUEST_CONTENT);
            return "clean request";
        };
        final HttpHeaders sanitizedRequestTrailers =
                HttpHeaders.of(HttpHeaderNames.CONTENT_MD5, "it's the secret");
        final Function<HttpHeaders, HttpHeaders> requestTrailersSanitizer = headers -> {
            assertThat(headers).isEqualTo(REQUEST_TRAILERS);
            return sanitizedRequestTrailers;
        };

        final HttpHeaders sanitizedResponseHeaders =
                HttpHeaders.of(HttpHeaderNames.CONTENT_TYPE, "where are the cookies?");
        final Function<HttpHeaders, HttpHeaders> responseHeadersSanitizer = headers -> {
            assertThat(headers).isEqualTo(RESPONSE_HEADERS);
            return sanitizedResponseHeaders;
        };
        final Function<Object, Object> responseContentSanitizer = content -> {
            assertThat(content).isEqualTo(RESPONSE_CONTENT);
            return "clean response";
        };
        final HttpHeaders sanitizedResponseTrailers =
                HttpHeaders.of(HttpHeaderNames.CONTENT_MD5, "it's a secret");
        final Function<HttpHeaders, HttpHeaders> responseTrailersSanitizer = headers -> {
            assertThat(headers).isEqualTo(RESPONSE_TRAILERS);
            return sanitizedResponseTrailers;
        };

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
        service.serve(ctx, REQUEST);
        verify(logger, times(2)).isInfoEnabled();
        verify(logger).info(REQUEST_FORMAT, ctx,
                            "headers: " + sanitizedRequestHeaders + ", content: clean request" +
                            ", trailers: " + sanitizedRequestTrailers);
        verify(logger).info(RESPONSE_FORMAT, ctx,
                            "headers: " + sanitizedResponseHeaders + ", content: clean response" +
                            ", trailers: " + sanitizedResponseTrailers);
        verifyNoMoreInteractions(logger);
    }

    @Test
    public void sanitize_error() throws Exception {
        final IllegalStateException dirtyCause = new IllegalStateException("dirty");
        final AnticipatedException cleanCause = new AnticipatedException("clean");
        final Function<Throwable, Throwable> responseCauseSanitizer = cause -> {
            assertThat(cause).isSameAs(dirtyCause);
            return cleanCause;
        };
        final LoggingService service =
                LoggingService.builder()
                              .logger(logger)
                              .requestLogLevel(LogLevel.INFO)
                              .successfulResponseLogLevel(LogLevel.INFO)
                              .responseCauseSanitizer(responseCauseSanitizer)
                              .newDecorator().apply(delegate);
        when(log.responseCause()).thenReturn(dirtyCause);
        service.serve(ctx, REQUEST);
        verify(logger, times(2)).isInfoEnabled();
        verify(logger).info(REQUEST_FORMAT, ctx,
                            "headers: " + REQUEST_HEADERS +
                            ", content: " + REQUEST_CONTENT +
                            ", trailers: " + REQUEST_TRAILERS);
        verify(logger, times(1)).isWarnEnabled();
        verify(logger).warn(RESPONSE_FORMAT, ctx,
                            "headers: " + RESPONSE_HEADERS +
                            ", content: " + RESPONSE_CONTENT +
                            ", trailers: " + RESPONSE_TRAILERS,
                            cleanCause);
        verifyNoMoreInteractions(logger);
    }

    @Test
    public void sanitize_error_silenced() throws Exception {
        final IllegalStateException dirtyCause = new IllegalStateException("dirty");
        final Function<Throwable, Throwable> responseCauseSanitizer = cause -> {
            assertThat(cause).isSameAs(dirtyCause);
            return null;
        };
        final LoggingService service =
                LoggingService.builder()
                              .logger(logger)
                              .requestLogLevel(LogLevel.INFO)
                              .successfulResponseLogLevel(LogLevel.INFO)
                              .responseCauseSanitizer(responseCauseSanitizer)
                              .newDecorator().apply(delegate);
        when(log.responseCause()).thenReturn(dirtyCause);
        service.serve(ctx, REQUEST);
        verify(logger, times(2)).isInfoEnabled();
        verify(logger).info(REQUEST_FORMAT, ctx,
                            "headers: " + REQUEST_HEADERS +
                            ", content: " + REQUEST_CONTENT +
                            ", trailers: " + REQUEST_TRAILERS);
        verify(logger, times(1)).isWarnEnabled();
        verify(logger).warn(RESPONSE_FORMAT, ctx,
                            "headers: " + RESPONSE_HEADERS +
                            ", content: " + RESPONSE_CONTENT +
                            ", trailers: " + RESPONSE_TRAILERS);
        verifyNoMoreInteractions(logger);
    }

    @Test
    public void sample() throws Exception {
        final LoggingService service =
                LoggingService.builder()
                              .logger(logger)
                              .requestLogLevel(LogLevel.INFO)
                              .successfulResponseLogLevel(LogLevel.INFO)
                              .samplingRate(0.0f)
                              .newDecorator().apply(delegate);
        service.serve(ctx, REQUEST);
        verifyNoInteractions(logger);
    }
}
