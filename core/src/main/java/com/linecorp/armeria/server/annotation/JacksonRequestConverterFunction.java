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

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AsciiString;

/**
 * A {@link RequestConverterFunction} which converts a JSON body of
 * the {@link AggregatedHttpRequest} to an object using the default {@link ObjectMapper}.
 * The {@link RequestConverterFunction} is applied only when the {@code content-type} of the
 * {@link RequestHeaders} is {@link MediaType#JSON} or ends with {@code +json}.
 * Note that this {@link RequestConverterFunction} is applied to an annotated service by default,
 * so you don't have to specify this converter explicitly unless you want to use your own {@link ObjectMapper}.
 */
public final class JacksonRequestConverterFunction implements RequestConverterFunction {

    private static final ObjectMapper defaultObjectMapper = JacksonUtil.newDefaultObjectMapper();
    private static final Map<Class<?>, Boolean> skippableTypes;

    static {
        final Map<Class<?>, Boolean> tmp = new IdentityHashMap<>();
        tmp.put(byte[].class, true);
        tmp.put(HttpData.class, true);
        tmp.put(String.class, true);
        tmp.put(AsciiString.class, true);
        tmp.put(CharSequence.class, true);
        tmp.put(Object.class, true);
        skippableTypes = Collections.unmodifiableMap(tmp);
    }

    private final ObjectMapper mapper;
    private final ConcurrentMap<Type, ObjectReader> readers = new ConcurrentHashMap<>();

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
     * Converts the specified {@link AggregatedHttpRequest} to an object of {@code expectedResultType}.
     */
    @Override
    @Nullable
    public Object convertRequest(
            ServiceRequestContext ctx, AggregatedHttpRequest request, Class<?> expectedResultType,
            @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {

        final MediaType contentType = request.contentType();
        if (contentType != null && contentType.isJson()) {
            if (expectedResultType == TreeNode.class ||
                expectedResultType == JsonNode.class) {
                try {
                    return mapper.readTree(getContent(request, contentType));
                } catch (JsonProcessingException e) {
                    throw newConversionException(e);
                }
            }

            final ObjectReader reader = getObjectReader(expectedResultType,
                                                        expectedParameterizedResultType);
            if (reader != null) {
                final String content = getContent(request, contentType);
                try {
                    return reader.readValue(content);
                } catch (JsonProcessingException e) {
                    if (skippableTypes.containsKey(expectedResultType)) {
                        return RequestConverterFunction.fallthrough();
                    }

                    throw newConversionException(e);
                }
            }
        }
        return RequestConverterFunction.fallthrough();
    }

    private static String getContent(AggregatedHttpRequest request, MediaType contentType) {
        return request.content(contentType.charset(StandardCharsets.UTF_8));
    }

    @Nullable
    private ObjectReader getObjectReader(Class<?> expectedResultType,
                                         @Nullable ParameterizedType expectedParameterizedResultType) {
        if (expectedParameterizedResultType != null) {
            return readers.computeIfAbsent(expectedParameterizedResultType, type -> {
                return mapper.readerFor(new TypeReference<Object>() {
                    @Override
                    public Type getType() {
                        return type;
                    }
                });
            });
        }

        return readers.computeIfAbsent(expectedResultType, type -> mapper.readerFor((Class<?>) type));
    }

    private static IllegalArgumentException newConversionException(JsonProcessingException e) {
        return new IllegalArgumentException("failed to parse a JSON document: " + e, e);
    }
}
