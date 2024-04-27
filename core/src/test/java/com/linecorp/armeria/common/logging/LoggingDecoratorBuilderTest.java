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
package com.linecorp.armeria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.BiFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServiceRequestContext;

class LoggingDecoratorBuilderTest {

    @Nullable
    @SuppressWarnings("rawtypes")
    private static final BiFunction nullBiFunction = null;

    private static final String SANITIZED_HEADERS = "dummy header sanitizer";
    private static final String SANITIZED_CONTENT = "dummy content sanitizer";

    private static final BiFunction<? super RequestContext, ? super HttpHeaders, ?> HEADER_SANITIZER =
            (ctx, headers) -> {
                assertThat(ctx).isNotNull();
                assertThat(headers).isNotNull();
                return SANITIZED_HEADERS;
            };
    private static final BiFunction<? super RequestContext, Object, ?> CONTENT_SANITIZER =
            (ctx, content) -> {
                assertThat(ctx).isNotNull();
                assertThat(content).isNotNull();
                return SANITIZED_CONTENT;
            };
    private static final BiFunction<? super RequestContext, ? super Throwable, ?> CAUSE_SANITIZER =
            (ctx, cause) -> {
                assertThat(ctx).isNotNull();
                assertThat(cause).isNotNull();
                return "dummy cause sanitizer";
            };

    private Builder builder;
    private ServiceRequestContext ctx;
    private Throwable testCause;

    @BeforeEach
    void setUp() {
        builder = new Builder();
        ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        testCause = new AnticipatedException("test");
    }

    @Test
    void logger() {
        assertThatThrownBy(() -> builder.logger((Logger) null))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.logger()).isNull();

        final Logger logger = mock(Logger.class);
        builder.logger(logger);
        assertThat(builder.logger()).isEqualTo(logger);
    }

    @Test
    void loggerName() {
        assertThatThrownBy(() -> builder.logger((String) null))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.logger()).isNull();

        builder.logger("com.example.Foo");
        assertThat(builder.logger().getName()).isEqualTo("com.example.Foo");
    }

    @Test
    void requestLogLevel() {
        assertThatThrownBy(() -> builder.requestLogLevel(null))
                .isInstanceOf(NullPointerException.class);

        builder.requestLogLevel(LogLevel.ERROR);

        final RequestLogLevelMapper mapper = builder.requestLogLevelMapper();
        assertThat(mapper.apply(newRequestOnlyLog())).isEqualTo(LogLevel.ERROR);
    }

    @Test
    void requestLogShouldBeDebugByDefault() {
        final RequestLogLevelMapper mapper = builder.requestLogLevelMapper();
        assertThat(mapper).isNull();

        assertThat(LogWriter.builder().requestLogLevelMapper().apply(newRequestOnlyLog()))
                .isEqualTo(LogLevel.DEBUG);
    }

    @Test
    void responseLogLevelWithHttpStatus() {
        assertThatThrownBy(() -> builder.responseLogLevel(HttpStatus.OK, null))
                .isInstanceOf(NullPointerException.class);

        builder.responseLogLevel(HttpStatus.OK, LogLevel.INFO)
               .responseLogLevel(HttpStatus.BAD_REQUEST, LogLevel.WARN)
               .responseLogLevel(HttpStatus.INTERNAL_SERVER_ERROR, LogLevel.ERROR);

        final ResponseLogLevelMapper mapper = builder.responseLogLevelMapper();
        assertThat(mapper.apply(newRequestLog(HttpStatus.OK))).isEqualTo(LogLevel.INFO);
        assertThat(mapper.apply(newRequestLog(HttpStatus.BAD_REQUEST))).isEqualTo(LogLevel.WARN);
        assertThat(mapper.apply(newRequestLog(HttpStatus.INTERNAL_SERVER_ERROR))).isEqualTo(LogLevel.ERROR);
    }

    @Test
    void responseLogLevelWithHttpStatusClass() {
        assertThatThrownBy(() -> builder.responseLogLevel(HttpStatusClass.SUCCESS, null))
                .isInstanceOf(NullPointerException.class);

        builder.responseLogLevel(HttpStatusClass.SUCCESS, LogLevel.INFO)
               .responseLogLevel(HttpStatusClass.CLIENT_ERROR, LogLevel.WARN)
               .responseLogLevel(HttpStatusClass.SERVER_ERROR, LogLevel.ERROR);

        final ResponseLogLevelMapper mapper = builder.responseLogLevelMapper();
        assertThat(mapper.apply(newRequestLog(HttpStatus.OK))).isEqualTo(LogLevel.INFO);
        assertThat(mapper.apply(newRequestLog(HttpStatus.CREATED))).isEqualTo(LogLevel.INFO);
        assertThat(mapper.apply(newRequestLog(HttpStatus.BAD_REQUEST))).isEqualTo(LogLevel.WARN);
        assertThat(mapper.apply(newRequestLog(HttpStatus.UNAUTHORIZED))).isEqualTo(LogLevel.WARN);
        assertThat(mapper.apply(newRequestLog(HttpStatus.INTERNAL_SERVER_ERROR))).isEqualTo(LogLevel.ERROR);
        assertThat(mapper.apply(newRequestLog(HttpStatus.NOT_IMPLEMENTED))).isEqualTo(LogLevel.ERROR);
    }

    @Test
    void successfulResponseLogLevel() {
        assertThatThrownBy(() -> builder.successfulResponseLogLevel(null))
                .isInstanceOf(NullPointerException.class);

        builder.successfulResponseLogLevel(LogLevel.ERROR);

        final ResponseLogLevelMapper mapper = builder.responseLogLevelMapper();
        assertThat(mapper.apply(newRequestLog(HttpStatus.OK))).isEqualTo(LogLevel.ERROR);
    }

    @Test
    void successfulResponseLogLevelShouldBeDebugByDefault() {
        final ResponseLogLevelMapper mapper = LogWriter.builder().responseLogLevelMapper();
        assertThat(mapper.apply(newRequestLog(HttpStatus.OK))).isEqualTo(LogLevel.DEBUG);
    }

    @Test
    void failureResponseLogLevel() {
        assertThatThrownBy(() -> builder.failureResponseLogLevel(null))
                .isInstanceOf(NullPointerException.class);

        builder.failureResponseLogLevel(LogLevel.ERROR);

        final ResponseLogLevelMapper mapper = builder.responseLogLevelMapper();
        assertThat(mapper.apply(newRequestLog(HttpStatus.BAD_REQUEST, new RuntimeException())))
                .isEqualTo(LogLevel.ERROR);
    }

    @Test
    void failureResponseLogLevelShouldBeWarnByDefault() {
        final ResponseLogLevelMapper mapper = LogWriter.builder().responseLogLevelMapper();
        assertThat(mapper.apply(newRequestLog(HttpStatus.BAD_REQUEST, new RuntimeException())))
                .isEqualTo(LogLevel.WARN);
    }

    @Test
    void responseLogLevelInOrder() {
        builder.responseLogLevel(HttpStatus.OK, LogLevel.INFO)
               .responseLogLevel(HttpStatusClass.SUCCESS, LogLevel.DEBUG)
               .responseLogLevel(HttpStatus.BAD_REQUEST, LogLevel.WARN)
               .responseLogLevel(HttpStatus.BAD_REQUEST, LogLevel.ERROR);

        final ResponseLogLevelMapper mapper = builder.responseLogLevelMapper();
        assertThat(mapper.apply(newRequestLog(HttpStatus.OK))).isEqualTo(LogLevel.INFO);
        assertThat(mapper.apply(newRequestLog(HttpStatus.CREATED))).isEqualTo(LogLevel.DEBUG);
        assertThat(mapper.apply(newRequestLog(HttpStatus.BAD_REQUEST))).isEqualTo(LogLevel.WARN);
    }

    @Test
    void responseLogLevel() {
        builder.successfulResponseLogLevel(LogLevel.INFO)
               .responseLogLevel(HttpStatus.BAD_REQUEST, LogLevel.INFO)
               .responseLogLevel(HttpStatusClass.SERVER_ERROR, LogLevel.ERROR);

        final ResponseLogLevelMapper mapper = builder.responseLogLevelMapper();
        assertThat(mapper.apply(newRequestLog(HttpStatus.OK))).isEqualTo(LogLevel.INFO);
        assertThat(mapper.apply(newRequestLog(HttpStatus.BAD_REQUEST, new RuntimeException())))
                .isEqualTo(LogLevel.INFO);
        assertThat(mapper.apply(newRequestLog(HttpStatus.INTERNAL_SERVER_ERROR, new RuntimeException())))
                .isEqualTo(LogLevel.ERROR);
    }

    @Test
    void responseLogLevelWithThrowable() {
        builder.successfulResponseLogLevel(LogLevel.INFO)
               .responseLogLevel(NullPointerException.class, LogLevel.DEBUG)
               .responseLogLevel(HttpStatusClass.SERVER_ERROR, LogLevel.ERROR)
               .responseLogLevel(IllegalStateException.class, LogLevel.INFO)
               .responseLogLevel(RuntimeException.class, LogLevel.ERROR);

        final ResponseLogLevelMapper mapper = builder.responseLogLevelMapper();
        assertThat(mapper.apply(newRequestLog(HttpStatus.OK))).isEqualTo(LogLevel.INFO);
        assertThat(mapper.apply(newRequestLog(HttpStatus.BAD_REQUEST, new IllegalStateException())))
                .isEqualTo(LogLevel.INFO);
        assertThat(mapper.apply(newRequestLog(HttpStatus.INTERNAL_SERVER_ERROR, new RuntimeException())))
                .isEqualTo(LogLevel.ERROR);
        assertThat(mapper.apply(newRequestLog(HttpStatus.INTERNAL_SERVER_ERROR, new NullPointerException())))
                .isEqualTo(LogLevel.DEBUG);
        assertThat(mapper.apply(newRequestLog(HttpStatus.INTERNAL_SERVER_ERROR,
                                              new IllegalArgumentException())))
                .isEqualTo(LogLevel.ERROR);
    }

    @Test
    void requestLogLevelWithThrowable() {
        builder.requestLogLevel(IllegalStateException.class, LogLevel.ERROR)
               .requestLogLevel(RuntimeException.class, LogLevel.WARN)
               .requestLogLevel(LogLevel.INFO);

        final RequestLogLevelMapper mapper = builder.requestLogLevelMapper();
        assertThat(mapper.apply(newRequestOnlyLog(new IllegalStateException()))).isEqualTo(LogLevel.ERROR);
        assertThat(mapper.apply(newRequestOnlyLog(new RuntimeException()))).isEqualTo(LogLevel.WARN);
        assertThat(mapper.apply(newRequestOnlyLog(new IllegalArgumentException()))).isEqualTo(LogLevel.WARN);
    }

    @Test
    void requestHeadersSanitizer() {
        assertThatThrownBy(() -> builder.requestHeadersSanitizer(nullBiFunction))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.requestHeadersSanitizer()).isNull();

        builder.requestHeadersSanitizer(HEADER_SANITIZER);
        assertThat(builder.requestHeadersSanitizer().apply(ctx, HttpHeaders.of())).isEqualTo(SANITIZED_HEADERS);
    }

    @Test
    void responseHeadersSanitizer() {
        assertThatThrownBy(() -> builder.responseHeadersSanitizer(nullBiFunction))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.responseHeadersSanitizer()).isNull();

        builder.responseHeadersSanitizer(HEADER_SANITIZER);
        assertThat(builder.responseHeadersSanitizer().apply(ctx, HttpHeaders.of())).isEqualTo(
                SANITIZED_HEADERS);
    }

    @Test
    void requestTrailersSanitizer() {
        assertThatThrownBy(() -> builder.requestTrailersSanitizer(nullBiFunction))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.requestTrailersSanitizer()).isNull();

        builder.requestTrailersSanitizer(HEADER_SANITIZER);
        assertThat(builder.requestTrailersSanitizer().apply(ctx, HttpHeaders.of())).isEqualTo(
                SANITIZED_HEADERS);
    }

    @Test
    void responseTrailersSanitizer() {
        assertThatThrownBy(() -> builder.responseTrailersSanitizer(nullBiFunction))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.responseTrailersSanitizer()).isNull();

        builder.responseTrailersSanitizer(HEADER_SANITIZER);
        assertThat(builder.responseTrailersSanitizer().apply(ctx, HttpHeaders.of())).isEqualTo(
                SANITIZED_HEADERS);
    }

    @Test
    void headerSanitizer() {
        assertThatThrownBy(() -> builder.headersSanitizer(nullBiFunction))
                .isInstanceOf(NullPointerException.class);

        builder.headersSanitizer(HEADER_SANITIZER);
        assertThat(builder.requestHeadersSanitizer().apply(ctx, HttpHeaders.of())).isEqualTo(SANITIZED_HEADERS);
        assertThat(builder.responseHeadersSanitizer().apply(ctx, HttpHeaders.of())).isEqualTo(
                SANITIZED_HEADERS);
        assertThat(builder.requestTrailersSanitizer().apply(ctx, HttpHeaders.of())).isEqualTo(
                SANITIZED_HEADERS);
        assertThat(builder.responseTrailersSanitizer().apply(ctx, HttpHeaders.of())).isEqualTo(
                SANITIZED_HEADERS);
    }

    @Test
    void requestContentSanitizer() {
        assertThatThrownBy(() -> builder.requestContentSanitizer(nullBiFunction))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.requestContentSanitizer()).isNull();

        builder.requestContentSanitizer(CONTENT_SANITIZER);
        assertThat(builder.requestContentSanitizer().apply(ctx, "")).isEqualTo(SANITIZED_CONTENT);
    }

    @Test
    void responseContentSanitizer() {
        assertThatThrownBy(() -> builder.responseContentSanitizer(nullBiFunction))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.responseContentSanitizer()).isNull();

        builder.responseContentSanitizer(CONTENT_SANITIZER);
        assertThat(builder.responseContentSanitizer().apply(ctx, "")).isEqualTo(SANITIZED_CONTENT);
    }

    @Test
    void contentSanitizer() {
        assertThatThrownBy(() -> builder.contentSanitizer(nullBiFunction))
                .isInstanceOf(NullPointerException.class);

        builder.contentSanitizer(CONTENT_SANITIZER);
        assertThat(builder.requestContentSanitizer().apply(ctx, "")).isEqualTo(SANITIZED_CONTENT);
        assertThat(builder.responseContentSanitizer().apply(ctx, "")).isEqualTo(SANITIZED_CONTENT);
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void responseCauseSanitizer(boolean filterCause) {
        assertThatThrownBy(() -> builder.responseCauseFilter(null))
                .isInstanceOf(NullPointerException.class);
        final Logger logger = mock(Logger.class);
        when(logger.isWarnEnabled()).thenReturn(true);
        builder.responseCauseFilter(cause -> filterCause);
        builder.logger(logger)
               .requestLogLevel(LogLevel.OFF)
               .logWriter().logResponse(newRequestLog(HttpStatus.OK, testCause));

        if (filterCause) {
            verify(logger, times(2)).warn(anyString());
        } else {
            // Log for the request
            verify(logger, times(1)).warn(anyString());
            // Log for the response
            verify(logger, times(1)).warn(anyString(), eq(testCause));
        }
    }

    @Test
    void buildLogWriter() {
        final LogWriter logWriter = LogWriter.of();
        builder.logWriter(logWriter);
        assertThat(builder.logWriter()).isEqualTo(logWriter);
    }

    @Test
    void buildLogWriterWithoutLogWriter() {
        builder.responseContentSanitizer(CONTENT_SANITIZER)
               .requestHeadersSanitizer(HEADER_SANITIZER);
        final LogWriter logWriter = builder.logWriter();
        assertThat(logWriter).isNotNull();
        assertThat(logWriter).isInstanceOf(DefaultLogWriter.class);
    }

    @Test
    void buildLogWriterThrowsException() {
        final LogWriter logWriter = LogWriter.of();
        builder.logWriter(logWriter);
        assertThatThrownBy(() -> builder.responseContentSanitizer(CONTENT_SANITIZER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldMaskSensitiveHeadersByDefault() {
        final HttpRequest request = HttpRequest.builder()
                                               .header(HttpHeaderNames.AUTHORIZATION, "Bearer secret")
                                               .method(HttpMethod.GET)
                                               .path("/")
                                               .build();
        final ServiceRequestContext ctx = ServiceRequestContext.of(request);
        final RequestLogBuilder requestLogBuilder =
                RequestLog.builder(ctx);
        requestLogBuilder.endRequest();
        requestLogBuilder.responseHeaders(ResponseHeaders.builder()
                                                         .status(HttpStatus.OK)
                                                         .cookie(Cookie.ofSecure("session-id", "abcd"))
                                                         .build());
        requestLogBuilder.endResponse();
        final RequestLog requestLog = requestLogBuilder.whenComplete().join();

        final Logger logger0 = mock(Logger.class);
        when(logger0.isDebugEnabled()).thenReturn(true);
        // A LoggerWriter created with the default (empty) properties should mask sensitive headers.
        final LogWriter logWriter0 = builder.defaultLogger(logger0)
                                            .logWriter();
        logWriter0.logRequest(requestLog);
        verify(logger0).debug(contains("authorization=***"));
        logWriter0.logResponse(requestLog);
        verify(logger0).debug(contains("set-cookie=****"));

        final Logger logger1 = mock(Logger.class);
        // A LoggerWriter internally created with the properties should mask sensitive headers.
        when(logger1.isInfoEnabled()).thenReturn(true);
        final LogWriter logWriter1 = new Builder().logger(logger1)
                                                  .requestLogLevel(LogLevel.INFO)
                                                  .successfulResponseLogLevel(LogLevel.INFO)
                                                  .logWriter();
        logWriter1.logRequest(requestLog);
        verify(logger1).info(contains("authorization=***"));
        logWriter1.logResponse(requestLog);
        verify(logger1).info(contains("set-cookie=****"));
    }

    private static final class Builder extends LoggingDecoratorBuilder {
    }

    private static RequestOnlyLog newRequestOnlyLog() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx.logBuilder().endRequest();
        return ctx.logBuilder().whenRequestComplete().join();
    }

    private static RequestOnlyLog newRequestOnlyLog(Throwable cause) {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final RequestLogBuilder builder = RequestLog.builder(ctx);
        builder.endRequest(cause);
        return builder.whenRequestComplete().join();
    }

    private static RequestLog newRequestLog(HttpStatus status) {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final RequestLogBuilder builder = RequestLog.builder(ctx);
        builder.endRequest();
        builder.responseHeaders(ResponseHeaders.of(status));
        builder.endResponse();
        return builder.whenComplete().join();
    }

    private static RequestLog newRequestLog(HttpStatus status, Throwable cause) {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final RequestLogBuilder builder = RequestLog.builder(ctx);
        builder.endRequest();
        builder.responseHeaders(ResponseHeaders.of(status));
        builder.endResponse(cause);
        return builder.whenComplete().join();
    }
}
