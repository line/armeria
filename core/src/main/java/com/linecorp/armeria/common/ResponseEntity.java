/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.common;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Represent an entity of an HTTP response.
 */
@UnstableApi
public interface ResponseEntity<T> extends HttpEntity<T> {

    /**
     * Returns a newly created {@link ResponseEntity} with the specified {@link ResponseHeaders}.
     */
    static ResponseEntity<Void> of(ResponseHeaders headers) {
        return of(headers, null, HttpHeaders.of());
    }

    /**
     * Returns a newly created {@link ResponseEntity} with the specified {@link ResponseHeaders} and
     * {@code content}.
     */
    static <T> ResponseEntity<T> of(ResponseHeaders headers, T content) {
        return of(headers, content, HttpHeaders.of());
    }

    /**
     * Returns a newly created {@link ResponseEntity} with the specified {@link ResponseHeaders},
     * {@code content} and {@linkplain HttpHeaders trailers}.
     */
    static <T> ResponseEntity<T> of(ResponseHeaders headers, @Nullable T content, HttpHeaders trailers) {
        return new DefaultResponseEntity<>(headers, content, trailers);
    }

    /**
     * Returns the {@link ResponseHeaders} of this entity.
     */
    @Override
    ResponseHeaders headers();

    /**
     * Returns the {@link HttpStatus} of this entity.
     */
    default HttpStatus status() {
        return headers().status();
    }
}
