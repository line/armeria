/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.protobuf;

import static com.linecorp.armeria.server.protobuf.ProtobufRequestConverterFunctionProvider.toResultType;
import static com.linecorp.armeria.server.protobuf.ProtobufResponseConverterFunction.X_PROTOBUF;
import static java.lang.invoke.MethodType.methodType;
import static java.util.Objects.requireNonNull;

import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.Parser;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;

/**
 * A {@link RequestConverterFunction} which converts a Protocol Buffers or JSON body of
 * the {@link AggregatedHttpRequest} to an object.
 * The built-in parser of {@link Message} for Protocol Buffers is applied only when the {@code content-type} of
 * {@link RequestHeaders} is either one of {@link MediaType#PROTOBUF} or {@link MediaType#OCTET_STREAM} or
 * the {@link MediaType#subtype()} contains {@code "protobuf"}.
 * The {@link Parser} for JSON is applied only when the {@code content-type} of
 * the {@link RequestHeaders} is either {@link MediaType#JSON} or ends with {@code +json}.
 *
 * <h2>Conversion of multiple Protobuf messages</h2>
 * A sequence of Protocol Buffer messages can not be handled by this {@link RequestConverterFunction},
 * because Protocol Buffers wire format is not self-delimiting.
 * See
 * <a href="https://developers.google.com/protocol-buffers/docs/techniques#streaming">Streaming Multiple Messages</a>
 * for more information.
 * However, {@link Collection} types such as {@code List<Message>} and {@code Set<Message>} are supported
 * when converted from <a href="https://datatracker.ietf.org/doc/html/rfc7159#section-5">JSON array</a>.
 *
 * <p>Note that this {@link RequestConverterFunction} is applied to an annotated service by default,
 * so you don't have to specify this converter explicitly unless you want to use your own {@link Parser} and
 * {@link ExtensionRegistry}.
 */
@UnstableApi
public final class ProtobufRequestConverterFunction implements RequestConverterFunction {

    private static final ClassValue<MethodHandle> methodCache = new ClassValue<MethodHandle>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            try {
                final Class<?> builderClass = Class.forName(type.getName() + "$Builder");
                final Lookup publicLookup = MethodHandles.publicLookup();
                return publicLookup.findStatic(type, "newBuilder", methodType(builderClass));
            } catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException ignored) {
                return unknownMethodHandle;
            }
        }
    };

    private static final Parser defaultJsonParser = JsonFormat.parser().ignoringUnknownFields();
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final MethodHandle unknownMethodHandle;

    static {
        final MethodHandles.Lookup publicLookup = MethodHandles.publicLookup();
        final MethodType mt = methodType(void.class);

        MethodHandle methodHandle;
        try {
            methodHandle = publicLookup.findConstructor(ProtobufRequestConverterFunction.class, mt);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            methodHandle = null;
        }
        assert methodHandle != null;
        unknownMethodHandle = methodHandle;
    }

    private final ExtensionRegistry extensionRegistry;
    private final Parser jsonParser;
    private final ResultType resultType;

    ProtobufRequestConverterFunction(ResultType resultType) {
        this(defaultJsonParser, ExtensionRegistry.getEmptyRegistry(), resultType);
    }

    /**
     * Creates an instance with the default {@link Parser} and {@link ExtensionRegistry}.
     */
    public ProtobufRequestConverterFunction() {
        this(defaultJsonParser, ExtensionRegistry.getEmptyRegistry());
    }

    /**
     * Creates an instance with the specified {@link Parser} and {@link ExtensionRegistry}.
     */
    public ProtobufRequestConverterFunction(Parser jsonParser, ExtensionRegistry extensionRegistry) {
        this(jsonParser, extensionRegistry, ResultType.UNKNOWN);
    }

    private ProtobufRequestConverterFunction(Parser jsonParser, ExtensionRegistry extensionRegistry,
                                             ResultType resultType) {
        this.jsonParser = requireNonNull(jsonParser, "jsonParser");
        this.extensionRegistry = requireNonNull(extensionRegistry, "extensionRegistry");
        this.resultType = requireNonNull(resultType, "resultType");
    }

    @Nullable
    @Override
    public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpRequest request,
                                 Class<?> expectedResultType,
                                 @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {

        final MediaType contentType = request.contentType();
        final Charset charset = contentType == null ? StandardCharsets.UTF_8
                                                    : contentType.charset(StandardCharsets.UTF_8);

        if (resultType == ResultType.PROTOBUF ||
            (resultType == ResultType.UNKNOWN && Message.class.isAssignableFrom(expectedResultType))) {
            final Message.Builder messageBuilder = getMessageBuilder(expectedResultType);

            if (isProtobuf(contentType)) {
                try (InputStream is = request.content().toInputStream()) {
                    return messageBuilder.mergeFrom(is, extensionRegistry).build();
                }
            }

            if (isJson(contentType)) {
                jsonParser.merge(request.content(charset), messageBuilder);
                return messageBuilder.build();
            }
        }

        if (!isJson(contentType) || expectedParameterizedResultType == null) {
            return RequestConverterFunction.fallthrough();
        }

        ResultType resultType = this.resultType;
        if (resultType == ResultType.UNKNOWN) {
            resultType = toResultType(expectedParameterizedResultType);
        }

        if (resultType == ResultType.UNKNOWN || resultType == ResultType.PROTOBUF) {
            return RequestConverterFunction.fallthrough();
        }

        final Type[] typeArguments = expectedParameterizedResultType.getActualTypeArguments();
        final String content = request.content(charset);
        final JsonNode jsonNode = mapper.readTree(content);

        switch (resultType) {
            case LIST_PROTOBUF:
            case SET_PROTOBUF:
                if (jsonNode.isArray()) {
                    final ImmutableCollection.Builder<Message> builder;
                    if (resultType == ResultType.LIST_PROTOBUF) {
                        builder = ImmutableList.builderWithExpectedSize(jsonNode.size());
                    } else {
                        builder = ImmutableSet.builderWithExpectedSize(jsonNode.size());
                    }

                    for (JsonNode node : jsonNode) {
                        final Message.Builder msgBuilder = getMessageBuilder((Class<?>) typeArguments[0]);
                        jsonParser.merge(mapper.writeValueAsString(node), msgBuilder);
                        builder.add(msgBuilder.build());
                    }
                    return builder.build();
                }
                break;
            case MAP_PROTOBUF:
                if (jsonNode.isObject()) {
                    final ImmutableMap.Builder<String, Message> builder =
                            ImmutableMap.builderWithExpectedSize(jsonNode.size());

                    for (final Iterator<Entry<String, JsonNode>> i = jsonNode.fields(); i.hasNext();) {
                        final Entry<String, JsonNode> e = i.next();
                        final Message.Builder msgBuilder = getMessageBuilder((Class<?>) typeArguments[1]);
                        jsonParser.merge(mapper.writeValueAsString(e.getValue()), msgBuilder);
                        builder.put(e.getKey(), msgBuilder.build());
                    }
                    return builder.build();
                }
                break;
        }

        return RequestConverterFunction.fallthrough();
    }

    static boolean isProtobuf(@Nullable MediaType contentType) {
        return contentType == null ||
               contentType.is(MediaType.PROTOBUF) ||
               contentType.is(X_PROTOBUF) ||
               contentType.is(MediaType.OCTET_STREAM);
    }

    static boolean isJson(@Nullable MediaType contentType) {
        return contentType != null &&
               (contentType.is(MediaType.JSON) || contentType.subtype().endsWith("+json"));
    }

    private static Message.Builder getMessageBuilder(Class<?> clazz) {
        final MethodHandle methodHandle = methodCache.get(clazz);
        if (methodHandle == unknownMethodHandle) {
            throw new IllegalStateException("Failed to find a static newBuilder() method from " + clazz);
        }
        try {
            return (Message.Builder) methodHandle.invoke();
        } catch (Throwable throwable) {
            throw new IllegalStateException(
                    "Failed to create an empty instance of " + clazz + " using newBuilder() method", throwable);
        }
    }

    enum ResultType {
        UNKNOWN,
        PROTOBUF,
        LIST_PROTOBUF,
        SET_PROTOBUF,
        MAP_PROTOBUF
    }
}
