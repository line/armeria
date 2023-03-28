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
import org.springframework.http.HttpStatusCode;
import org.springframework.lang.Nullable;

/**
 * A version specific {@link AbstractServerHttpResponse} which implements the APIs that only exists in
 * Spring 6.
 */
abstract class AbstractServerHttpResponseVersionSpecific extends AbstractServerHttpResponse {

    @Nullable
    private HttpStatusCode statusCode;

    AbstractServerHttpResponseVersionSpecific(DataBufferFactory dataBufferFactory) {
        super(dataBufferFactory);
    }

    /**
     * Set the HTTP status code of the response.
     * Note that this method is only valid for Spring 6. Spring 5 takes {@link HttpStatus} as the input type.
     */
    @Override
    public boolean setStatusCode(@Nullable HttpStatusCode status) {
        if (state() == State.COMMITTED) {
            return false;
        } else {
            statusCode = status;
            return true;
        }
    }

    @Override
    @Nullable
    public HttpStatusCode getStatusCode() {
        return statusCode;
    }

    @Override
    public boolean setRawStatusCode(@Nullable Integer statusCode) {
        return setStatusCode(statusCode != null ? HttpStatusCode.valueOf(statusCode) : null);
    }

    @Deprecated
    @Override
    @Nullable
    public Integer getRawStatusCode() {
        return statusCode != null ? statusCode.value() : null;
    }
}
