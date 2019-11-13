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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.logging.RequestLogListener;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.internal.AnticipatedException;

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

    private static final String REQUEST_FORMAT = "Request: {}";
    private static final String RESPONSE_FORMAT = "Response: {}";

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private ServiceRequestContext ctx;

    @Mock
    private Logger logger;

    @Mock
    private RequestLog log;

    @Mock
    private Service<HttpRequest, HttpResponse> delegate;

    @Before
    public void setUp() {
        // Logger only logs INFO + WARN.
        when(logger.isInfoEnabled()).thenReturn(true);
        when(logger.isWarnEnabled()).thenReturn(true);

        when(ctx.log()).thenReturn(log);
        doAnswer(invocation -> {
            final RequestLogListener listener = invocation.getArgument(0);
            listener.onRequestLog(log);
            return null;
        }).when(log).addListener(isA(RequestLogListener.class), isA(RequestLogAvailability.class));
        when(ctx.logger()).thenReturn(logger);

        when(log.toStringRequestOnly(any(), any(), any())).thenAnswer(
                invocation -> {
                    final Function<HttpHeaders, HttpHeaders> headersSanitizer = invocation.getArgument(0);
                    final Function<Object, Object> contentSanitizer = invocation.getArgument(1);
                    final Function<HttpHeaders, HttpHeaders> trailersSanitizer = invocation.getArgument(2);
                    return "headers: " + headersSanitizer.apply(REQUEST_HEADERS) +
                           ", content: " + contentSanitizer.apply(REQUEST_CONTENT) +
                           ", trailers: " + trailersSanitizer.apply(REQUEST_TRAILERS);
                });
        when(log.toStringResponseOnly(any(), any(), any())).thenAnswer(
                invocation -> {
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
        final LoggingService<HttpRequest, HttpResponse> service =
                LoggingService.builder().<HttpRequest, HttpResponse>newDecorator().apply(delegate);
        service.serve(ctx, REQUEST);
        verify(logger, never()).info(isA(String.class), isA(Object.class));
    }

    @Test
    public void defaults_error() throws Exception {
        final LoggingService<HttpRequest, HttpResponse> service =
                LoggingService.builder().<HttpRequest, HttpResponse>newDecorator().apply(delegate);
        final IllegalStateException cause = new IllegalStateException("Failed");
        when(log.responseCause()).thenReturn(cause);
        service.serve(ctx, REQUEST);
        verify(logger).warn(REQUEST_FORMAT,
                            "headers: " + REQUEST_HEADERS + ", content: " + REQUEST_CONTENT +
                            ", trailers: " + REQUEST_TRAILERS);
        verify(logger).warn(RESPONSE_FORMAT,
                            "headers: " + RESPONSE_HEADERS + ", content: " + RESPONSE_CONTENT +
                            ", trailers: " + RESPONSE_TRAILERS,
                            cause);
    }

    @Test
    public void infoLevel() throws Exception {
        final LoggingService<HttpRequest, HttpResponse> service =
                LoggingService.builder()
                              .requestLogLevel(LogLevel.INFO)
                              .successfulResponseLogLevel(LogLevel.INFO)
                              .<HttpRequest, HttpResponse>newDecorator().apply(delegate);
        service.serve(ctx, REQUEST);
        verify(logger).info(REQUEST_FORMAT,
                            "headers: " + REQUEST_HEADERS + ", content: " + REQUEST_CONTENT +
                            ", trailers: " + REQUEST_TRAILERS);
        verify(logger).info(RESPONSE_FORMAT,
                            "headers: " + RESPONSE_HEADERS + ", content: " + RESPONSE_CONTENT +
                            ", trailers: " + RESPONSE_TRAILERS);
    }

    @Test
    public void  map_request_level_test_header_as_warn() throws Exception {
        when(log.requestHeaders()).thenAnswer(invocation -> RequestHeaders.of(HttpMethod.GET, "/",
                                                                              "x-req", "test",
                                                                              "x-res", "test"));

        final LoggingService<HttpRequest, HttpResponse> service =
                LoggingService.builder()
                              .requestLogLevel(LogLevel.INFO)
                              .successfulResponseLogLevel(LogLevel.INFO)
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
                        .<HttpRequest, HttpResponse>newDecorator().apply(delegate);

        service.serve(ctx, REQUEST);
        verify(logger).warn(REQUEST_FORMAT,
                            "headers: " + REQUEST_HEADERS + ", content: " + REQUEST_CONTENT +
                            ", trailers: " + REQUEST_TRAILERS);
        verify(logger).warn(RESPONSE_FORMAT,
                            "headers: " + RESPONSE_HEADERS + ", content: " + RESPONSE_CONTENT +
                            ", trailers: " + RESPONSE_TRAILERS);
    }

    @Test
    public void map_request_level_header_as_info() throws Exception {
        when(log.requestHeaders()).thenAnswer(invocation -> RequestHeaders.of(HttpMethod.GET, "/"));

        final LoggingService<HttpRequest, HttpResponse> service =
                LoggingService.builder()
                              .requestLogLevel(LogLevel.INFO)
                              .successfulResponseLogLevel(LogLevel.INFO)
                              .requestLogLevelMapper(log -> {
                                  if (log.requestHeaders().contains("x-test")) {
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
                        .<HttpRequest, HttpResponse>newDecorator().apply(delegate);

        service.serve(ctx, REQUEST);
        verify(logger).info(REQUEST_FORMAT,
                            "headers: " + REQUEST_HEADERS + ", content: " + REQUEST_CONTENT +
                            ", trailers: " + REQUEST_TRAILERS);
        verify(logger).info(RESPONSE_FORMAT,
                            "headers: " + RESPONSE_HEADERS + ", content: " + RESPONSE_CONTENT +
                            ", trailers: " + RESPONSE_TRAILERS);
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

        final LoggingService<HttpRequest, HttpResponse> service =
                LoggingService.builder()
                              .requestLogLevel(LogLevel.INFO)
                              .successfulResponseLogLevel(LogLevel.INFO)
                              .requestHeadersSanitizer(requestHeadersSanitizer)
                              .requestContentSanitizer(requestContentSanitizer)
                              .requestTrailersSanitizer(requestTrailersSanitizer)
                              .requestTrailersSanitizer(requestTrailersSanitizer)
                              .responseHeadersSanitizer(responseHeadersSanitizer)
                              .responseContentSanitizer(responseContentSanitizer)
                              .responseTrailersSanitizer(responseTrailersSanitizer)
                              .<HttpRequest, HttpResponse>newDecorator().apply(delegate);
        service.serve(ctx, REQUEST);
        verify(logger).info(REQUEST_FORMAT,
                            "headers: " + sanitizedRequestHeaders + ", content: clean request" +
                            ", trailers: " + sanitizedRequestTrailers);
        verify(logger).info(RESPONSE_FORMAT,
                            "headers: " + sanitizedResponseHeaders + ", content: clean response" +
                            ", trailers: " + sanitizedResponseTrailers);
    }

    @Test
    public void sanitize_error() throws Exception {
        final IllegalStateException dirtyCause = new IllegalStateException("dirty");
        final AnticipatedException cleanCause = new AnticipatedException("clean");
        final Function<Throwable, Throwable> responseCauseSanitizer = cause -> {
            assertThat(cause).isSameAs(dirtyCause);
            return cleanCause;
        };
        final LoggingService<HttpRequest, HttpResponse> service =
                LoggingService.builder()
                              .requestLogLevel(LogLevel.INFO)
                              .successfulResponseLogLevel(LogLevel.INFO)
                              .responseCauseSanitizer(responseCauseSanitizer)
                              .<HttpRequest, HttpResponse>newDecorator().apply(delegate);
        when(log.responseCause()).thenReturn(dirtyCause);
        service.serve(ctx, REQUEST);
        verify(logger).info(REQUEST_FORMAT, "headers: " + REQUEST_HEADERS +
                                            ", content: " + REQUEST_CONTENT +
                                            ", trailers: " + REQUEST_TRAILERS);
        verify(logger).warn(RESPONSE_FORMAT, "headers: " + RESPONSE_HEADERS +
                                             ", content: " + RESPONSE_CONTENT +
                                             ", trailers: " + RESPONSE_TRAILERS,
                            cleanCause);
    }

    @Test
    public void sanitize_error_silenced() throws Exception {
        final IllegalStateException dirtyCause = new IllegalStateException("dirty");
        final Function<Throwable, Throwable> responseCauseSanitizer = cause -> {
            assertThat(cause).isSameAs(dirtyCause);
            return null;
        };
        final LoggingService<HttpRequest, HttpResponse> service =
                LoggingService.builder()
                              .requestLogLevel(LogLevel.INFO)
                              .successfulResponseLogLevel(LogLevel.INFO)
                              .responseCauseSanitizer(responseCauseSanitizer)
                              .<HttpRequest, HttpResponse>newDecorator().apply(delegate);
        when(log.responseCause()).thenReturn(dirtyCause);
        service.serve(ctx, REQUEST);
        verify(logger).info(REQUEST_FORMAT, "headers: " + REQUEST_HEADERS + ", content: " + REQUEST_CONTENT +
                                            ", trailers: " + REQUEST_TRAILERS);
        verify(logger).warn(RESPONSE_FORMAT, "headers: " + RESPONSE_HEADERS + ", content: " + RESPONSE_CONTENT +
                                             ", trailers: " + RESPONSE_TRAILERS);
    }

    @Test
    public void sample() throws Exception {
        final LoggingService<HttpRequest, HttpResponse> service =
                LoggingService.builder()
                              .requestLogLevel(LogLevel.INFO)
                              .successfulResponseLogLevel(LogLevel.INFO)
                              .samplingRate(0.0f)
                              .<HttpRequest, HttpResponse>newDecorator().apply(delegate);
        service.serve(ctx, REQUEST);
        verifyNoInteractions(logger);
    }
}
