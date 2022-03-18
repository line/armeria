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
import static org.mockito.Mockito.mock;

import java.util.function.BiFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Functions;
import com.linecorp.armeria.server.ServiceRequestContext;

class LoggingDecoratorBuilderTest {

    @Nullable
    @SuppressWarnings("rawtypes")
    private static final BiFunction nullBiFunction = null;

    private static final BiFunction<? super RequestContext, ? super HttpHeaders, ?> HEADER_SANITIZER =
            (ctx, headers) -> {
                assertThat(ctx).isNotNull();
                assertThat(headers).isNotNull();
                return "dummy header sanitizer";
            };
    private static final BiFunction<? super RequestContext, Object, ?> CONTENT_SANITIZER =
            (ctx, content) -> {
                assertThat(ctx).isNotNull();
                assertThat(content).isNotNull();
                return "dummy content sanitizer";
            };
    private static final BiFunction<? super RequestContext, ? super Throwable, ?> CAUSE_SANITIZER =
            (ctx, cause) -> {
                assertThat(ctx).isNotNull();
                assertThat(cause).isNotNull();
                return "dummy cause sanitizer";
            };

    private Builder builder;

    @BeforeEach
    void setUp() {
        builder = new Builder();
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
        assertThat(mapper.apply(newRequestOnlyLog())).isEqualTo(LogLevel.DEBUG);
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
        final ResponseLogLevelMapper mapper = builder.responseLogLevelMapper();
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
        final ResponseLogLevelMapper mapper = builder.responseLogLevelMapper();
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
    void requestHeadersSanitizer() {
        assertThatThrownBy(() -> builder.requestHeadersSanitizer(nullBiFunction))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.requestHeadersSanitizer()).isEqualTo(Functions.second());

        builder.requestHeadersSanitizer(HEADER_SANITIZER);
        assertThat(builder.requestHeadersSanitizer()).isEqualTo(HEADER_SANITIZER);
    }

    @Test
    void responseHeadersSanitizer() {
        assertThatThrownBy(() -> builder.responseHeadersSanitizer(nullBiFunction))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.responseHeadersSanitizer()).isEqualTo(Functions.second());

        builder.responseHeadersSanitizer(HEADER_SANITIZER);
        assertThat(builder.responseHeadersSanitizer()).isEqualTo(HEADER_SANITIZER);
    }

    @Test
    void requestTrailersSanitizer() {
        assertThatThrownBy(() -> builder.requestTrailersSanitizer(nullBiFunction))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.requestTrailersSanitizer()).isEqualTo(Functions.second());

        builder.requestTrailersSanitizer(HEADER_SANITIZER);
        assertThat(builder.requestTrailersSanitizer()).isEqualTo(HEADER_SANITIZER);
    }

    @Test
    void responseTrailersSanitizer() {
        assertThatThrownBy(() -> builder.responseTrailersSanitizer(nullBiFunction))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.responseTrailersSanitizer()).isEqualTo(Functions.second());

        builder.responseTrailersSanitizer(HEADER_SANITIZER);
        assertThat(builder.responseTrailersSanitizer()).isEqualTo(HEADER_SANITIZER);
    }

    @Test
    void headerSanitizer() {
        assertThatThrownBy(() -> builder.headersSanitizer(nullBiFunction))
                .isInstanceOf(NullPointerException.class);

        builder.headersSanitizer(HEADER_SANITIZER);
        assertThat(builder.requestHeadersSanitizer()).isEqualTo(HEADER_SANITIZER);
        assertThat(builder.responseHeadersSanitizer()).isEqualTo(HEADER_SANITIZER);
        assertThat(builder.requestTrailersSanitizer()).isEqualTo(HEADER_SANITIZER);
        assertThat(builder.responseTrailersSanitizer()).isEqualTo(HEADER_SANITIZER);
    }

    @Test
    void requestContentSanitizer() {
        assertThatThrownBy(() -> builder.requestContentSanitizer(nullBiFunction))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.requestContentSanitizer()).isEqualTo(Functions.second());

        builder.requestContentSanitizer(CONTENT_SANITIZER);
        assertThat(builder.requestContentSanitizer()).isEqualTo(CONTENT_SANITIZER);
    }

    @Test
    void responseContentSanitizer() {
        assertThatThrownBy(() -> builder.responseContentSanitizer(nullBiFunction))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.responseContentSanitizer()).isEqualTo(Functions.second());

        builder.responseContentSanitizer(CONTENT_SANITIZER);
        assertThat(builder.responseContentSanitizer()).isEqualTo(CONTENT_SANITIZER);
    }

    @Test
    void contentSanitizer() {
        assertThatThrownBy(() -> builder.contentSanitizer(nullBiFunction))
                .isInstanceOf(NullPointerException.class);

        builder.contentSanitizer(CONTENT_SANITIZER);
        assertThat(builder.requestContentSanitizer()).isEqualTo(CONTENT_SANITIZER);
        assertThat(builder.responseContentSanitizer()).isEqualTo(CONTENT_SANITIZER);
    }

    @Test
    void responseCauseSanitizer() {
        assertThatThrownBy(() -> builder.responseCauseSanitizer(nullBiFunction))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.responseCauseSanitizer()).isEqualTo(Functions.second());

        builder.responseCauseSanitizer(CAUSE_SANITIZER);
        assertThat(builder.responseCauseSanitizer()).isEqualTo(CAUSE_SANITIZER);
    }

    private static final class Builder extends LoggingDecoratorBuilder {
    }

    private static RequestOnlyLog newRequestOnlyLog() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx.logBuilder().endRequest();
        return ctx.logBuilder().whenRequestComplete().join();
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
