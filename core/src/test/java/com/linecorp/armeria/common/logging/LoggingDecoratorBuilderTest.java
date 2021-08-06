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
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Functions;

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
    void requestLog() {
        assertThatThrownBy(() -> builder.requestLogLevel(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.requestLogLevel()).isEqualTo(LogLevel.DEBUG);

        builder.requestLogLevel(LogLevel.ERROR);
        assertThat(builder.requestLogLevel()).isEqualTo(LogLevel.ERROR);
    }

    @Test
    public void successfulResponseLogLevel() {
        assertThatThrownBy(() -> builder.successfulResponseLogLevel(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.successfulResponseLogLevel()).isEqualTo(LogLevel.DEBUG);

        builder.successfulResponseLogLevel(LogLevel.ERROR);
        assertThat(builder.successfulResponseLogLevel()).isEqualTo(LogLevel.ERROR);
    }

    @Test
    void failureResponseLogLevel() {
        assertThatThrownBy(() -> builder.failureResponseLogLevel(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(builder.failedResponseLogLevel()).isEqualTo(LogLevel.WARN);

        builder.failureResponseLogLevel(LogLevel.ERROR);
        assertThat(builder.failedResponseLogLevel()).isEqualTo(LogLevel.ERROR);
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
}
