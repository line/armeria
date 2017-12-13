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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.MediaType;

/**
 * A default implementation of a {@link RequestConverterFunction} which converts a JSON body of
 * the {@link AggregatedHttpMessage} to an object by {@link ObjectMapper}.
 */
public class JacksonRequestConverterFunction implements RequestConverterFunction {

    private static final Logger logger = LoggerFactory.getLogger(JacksonRequestConverterFunction.class);

    private final ObjectMapper mapper;
    private final ConcurrentMap<Class<?>, ObjectReader> readers = new ConcurrentHashMap<>();

    /**
     * Creates an instance with the default {@link ObjectMapper}.
     */
    public JacksonRequestConverterFunction() {
        this(new ObjectMapper());
    }

    /**
     * Creates an instance with the specified {@link ObjectMapper}.
     */
    public JacksonRequestConverterFunction(ObjectMapper mapper) {
        this.mapper = requireNonNull(mapper, "mapper");
    }

    /**
     * Returns whether the specified {@link AggregatedHttpMessage} is able to be consumed.
     */
    @Override
    public boolean accept(AggregatedHttpMessage request, Class<?> expectedResultType) {
        final MediaType contentType = request.headers().contentType();
        // TODO(hyangtack) Do benchmark tests to decide whether we add a cache to MediaType#parse.
        if (contentType != null && contentType.is(MediaType.JSON)) {
            try {
                return readers.computeIfAbsent(expectedResultType, mapper::readerFor) != null;
            } catch (Throwable cause) {
                logger.warn(JacksonRequestConverterFunction.class.getName() +
                            " cannot read a JSON of '" + request.content().toStringUtf8() +
                            "' as a type '" + expectedResultType.getName() + '\'', cause);
            }
        }
        return false;
    }

    /**
     * Converts the specified {@link AggregatedHttpMessage} to an object of {@code expectedResultType}.
     */
    @Override
    public Object convert(AggregatedHttpMessage request, Class<?> expectedResultType) throws Exception {
        final ObjectReader reader = readers.get(expectedResultType);
        assert reader != null;
        final String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        assert contentType != null;

        final Charset charset = MediaType.parse(contentType).charset()
                                         .orElse(StandardCharsets.UTF_8);
        return reader.readValue(request.content().toString(charset));
    }
}
