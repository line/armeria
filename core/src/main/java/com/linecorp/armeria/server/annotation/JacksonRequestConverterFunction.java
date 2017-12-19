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

package com.linecorp.armeria.server.annotation;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A default implementation of a {@link RequestConverterFunction} which converts a JSON body of
 * the {@link AggregatedHttpMessage} to an object by {@link ObjectMapper}.
 */
public class JacksonRequestConverterFunction implements RequestConverterFunction {

    private static final ObjectMapper defaultObjectMapper = new ObjectMapper();

    private final ObjectMapper mapper;
    private final ConcurrentMap<Class<?>, ObjectReader> readers = new ConcurrentHashMap<>();

    /**
     * Creates an instance with the default {@link ObjectMapper}.
     */
    public JacksonRequestConverterFunction() {
        this(defaultObjectMapper);
    }

    /**
     * Creates an instance with the specified {@link ObjectMapper}.
     */
    public JacksonRequestConverterFunction(ObjectMapper mapper) {
        this.mapper = requireNonNull(mapper, "mapper");
    }

    /**
     * Converts the specified {@link AggregatedHttpMessage} to an object of {@code expectedResultType}.
     */
    @Override
    public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                 Class<?> expectedResultType) throws Exception {

        final MediaType contentType = request.headers().contentType();
        if (contentType != null && (contentType.is(MediaType.JSON) ||
                                    contentType.subtype().endsWith("+json"))) {
            final ObjectReader reader = readers.computeIfAbsent(expectedResultType, mapper::readerFor);
            if (reader != null) {
                return reader.readValue(request.content().toString(
                        contentType.charset().orElse(StandardCharsets.UTF_8)));
            }
        }
        return RequestConverterFunction.fallthrough();
    }
}
