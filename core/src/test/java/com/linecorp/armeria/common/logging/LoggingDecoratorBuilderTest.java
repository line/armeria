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

package com.linecorp.armeria.common.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.linecorp.armeria.common.HttpHeaders;

public class LoggingDecoratorBuilderTest {

    private Builder builder;
    private Function<? super HttpHeaders, ?> headerSanitizer;
    private Function<Object, ?> contentSanitizer;
    private Function<? super Throwable, ?> causeSanitizer;

    @BeforeEach
    void setUp() {
        builder = new Builder();

        headerSanitizer = header -> "dummy header sanitizer";
        contentSanitizer = object -> "dummy content sanitizer";
        causeSanitizer = object -> "dummy cause sanitizer";
    }

    @Test
    void logger() {
        assertThrows(NullPointerException.class, () -> builder.logger(null));
        assertNull(builder.logger());

        final Logger logger = mock(Logger.class);
        builder.logger(logger);
        assertEquals(logger, builder.logger());
    }

    @Test
    void requestLog() {
        assertThrows(NullPointerException.class, () -> builder.requestLogLevel(null));
        assertEquals(LogLevel.TRACE, builder.requestLogLevel());

        builder.requestLogLevel(LogLevel.ERROR);
        assertEquals(LogLevel.ERROR, builder.requestLogLevel());
    }

    @Test
    public void successfulResponseLogLevel() {
        assertThrows(NullPointerException.class, () -> builder.successfulResponseLogLevel(null));
        assertEquals(LogLevel.TRACE, builder.successfulResponseLogLevel());

        builder.successfulResponseLogLevel(LogLevel.ERROR);
        assertEquals(LogLevel.ERROR, builder.successfulResponseLogLevel());
    }

    @Test
    void failureResponseLogLevel() {
        assertThrows(NullPointerException.class, () -> builder.failureResponseLogLevel(null));
        assertEquals(LogLevel.WARN, builder.failedResponseLogLevel());

        builder.failureResponseLogLevel(LogLevel.ERROR);
        assertEquals(LogLevel.ERROR, builder.failedResponseLogLevel());
    }

    @Test
    void requestHeadersSanitizer() {
        assertThrows(NullPointerException.class, () -> builder.requestHeadersSanitizer(null));
        assertEquals(Function.identity(), builder.requestHeadersSanitizer());

        builder.requestHeadersSanitizer(headerSanitizer);
        assertEquals(headerSanitizer, builder.requestHeadersSanitizer());
    }

    @Test
    void responseHeadersSanitizer() {
        assertThrows(NullPointerException.class, () -> builder.responseHeadersSanitizer(null));
        assertEquals(Function.identity(), builder.responseHeadersSanitizer());

        builder.responseHeadersSanitizer(headerSanitizer);
        assertEquals(headerSanitizer, builder.responseHeadersSanitizer());
    }

    @Test
    void requestTrailersSanitizer() {
        assertThrows(NullPointerException.class, () -> builder.requestTrailersSanitizer(null));
        assertEquals(Function.identity(), builder.requestTrailersSanitizer());

        builder.requestTrailersSanitizer(headerSanitizer);
        assertEquals(headerSanitizer, builder.requestTrailersSanitizer());
    }

    @Test
    void responseTrailersSanitizer() {
        assertThrows(NullPointerException.class, () -> builder.responseTrailersSanitizer(null));
        assertEquals(Function.identity(), builder.responseTrailersSanitizer());

        builder.responseTrailersSanitizer(headerSanitizer);
        assertEquals(headerSanitizer, builder.responseTrailersSanitizer());
    }

    @Test
    void headerSanitizer() {
        assertThrows(NullPointerException.class, () -> builder.headersSanitizer(null));

        builder.headersSanitizer(headerSanitizer);
        assertEquals(headerSanitizer, builder.requestHeadersSanitizer());
        assertEquals(headerSanitizer, builder.responseHeadersSanitizer());
        assertEquals(headerSanitizer, builder.requestTrailersSanitizer());
        assertEquals(headerSanitizer, builder.responseTrailersSanitizer());
    }

    @Test
    void requestContentSanitizer() {
        assertThrows(NullPointerException.class, () -> builder.requestContentSanitizer(null));
        assertEquals(Function.identity(), builder.requestContentSanitizer());

        builder.requestContentSanitizer(contentSanitizer);
        assertEquals(contentSanitizer, builder.requestContentSanitizer());
    }

    @Test
    void responseContentSanitizer() {
        assertThrows(NullPointerException.class, () -> builder.responseContentSanitizer(null));
        assertEquals(Function.identity(), builder.responseContentSanitizer());

        builder.responseContentSanitizer(contentSanitizer);
        assertEquals(contentSanitizer, builder.responseContentSanitizer());
    }

    @Test
    void contentSanitizer() {
        assertThrows(NullPointerException.class, () -> builder.contentSanitizer(null));

        builder.contentSanitizer(contentSanitizer);
        assertEquals(contentSanitizer, builder.requestContentSanitizer());
        assertEquals(contentSanitizer, builder.responseContentSanitizer());
    }

    @Test
    void responseCauseSanitizer() {
        assertThrows(NullPointerException.class, () -> builder.responseCauseSanitizer(null));
        assertEquals(Function.identity(), builder.responseCauseSanitizer());

        builder.responseCauseSanitizer(causeSanitizer);
        assertEquals(causeSanitizer, builder.responseCauseSanitizer());
    }

    private static final class Builder extends LoggingDecoratorBuilder<Builder> {
    }
}
