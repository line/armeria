/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.spring.web.reactive;

import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpStatus;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A version specific {@link AbstractServerHttpResponse} which implements the APIs that only exists in
 * Spring 5.
 */
abstract class AbstractServerHttpResponseVersionSpecific extends AbstractServerHttpResponse {

    @Nullable
    private Integer statusCode;

    AbstractServerHttpResponseVersionSpecific(DataBufferFactory dataBufferFactory) {
        super(dataBufferFactory);
    }

    /**
     * Set the HTTP status code of the response.
     * Note that this method is only valid for Spring 5. {@code HttpStatusCode} is used as the input type in
     * Spring 6.
     */
    @Override
    public boolean setStatusCode(@Nullable HttpStatus status) {
        if (state() == State.COMMITTED) {
            return false;
        } else {
            statusCode = status != null ? status.value() : null;
            return true;
        }
    }

    @Override
    @Nullable
    public HttpStatus getStatusCode() {
        return statusCode != null ? HttpStatus.resolve(statusCode) : null;
    }

    @Override
    public boolean setRawStatusCode(@Nullable Integer statusCode) {
        if (state() == State.COMMITTED) {
            return false;
        } else {
            this.statusCode = statusCode;
            return true;
        }
    }

    @Override
    @Nullable
    public Integer getRawStatusCode() {
        return statusCode;
    }
}
