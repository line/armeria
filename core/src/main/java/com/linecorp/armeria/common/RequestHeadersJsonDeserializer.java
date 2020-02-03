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

package com.linecorp.armeria.common;

import com.fasterxml.jackson.databind.JsonDeserializer;

/**
 * Jackson {@link JsonDeserializer} for {@link RequestHeaders}.
 */
final class RequestHeadersJsonDeserializer extends AbstractHttpHeadersJsonDeserializer<RequestHeaders> {

    private static final long serialVersionUID = 3242326707433770361L;

    /**
     * Creates a new instance.
     */
    RequestHeadersJsonDeserializer() {
        super(RequestHeaders.class);
    }

    @Override
    RequestHeadersBuilder newBuilder() {
        return RequestHeaders.builder();
    }
}
