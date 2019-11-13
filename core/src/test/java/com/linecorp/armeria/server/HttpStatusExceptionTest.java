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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpStatus;

class HttpStatusExceptionTest {

    @Test
    void withCode() {
        final HttpStatusException cause = HttpStatusException.of(404);
        assertThat(cause).hasNoCause();
        assertThat(cause).hasMessage(HttpStatus.NOT_FOUND.toString());
        assertThat(cause.httpStatus()).isSameAs(HttpStatus.NOT_FOUND);
    }

    @Test
    void withCodeAndCause() {
        final Exception causeOfCause = new Exception();
        final HttpStatusException cause = HttpStatusException.of(503, causeOfCause);
        assertThat(cause).hasCause(causeOfCause);
        assertThat(cause).hasMessage(HttpStatus.SERVICE_UNAVAILABLE.toString());
        assertThat(cause.httpStatus()).isSameAs(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void withUnusualCode() {
        final int statusCode = 1000;
        final HttpStatusException cause = HttpStatusException.of(statusCode);
        assertThat(cause.httpStatus().code()).isEqualTo(statusCode);
        assertThat(cause.httpStatus()).isEqualTo(HttpStatus.valueOf(statusCode))
                                      .isNotSameAs(HttpStatus.valueOf(statusCode));
    }
}
