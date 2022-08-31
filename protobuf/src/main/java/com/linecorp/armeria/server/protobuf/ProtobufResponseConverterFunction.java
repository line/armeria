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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.internal.server.ResponseConversionUtil.aggregateFrom;
import static com.linecorp.armeria.internal.server.annotation.ClassUtil.typeToClass;
import static com.linecorp.armeria.internal.server.annotation.ClassUtil.unwrapUnaryAsyncType;
import static com.linecorp.armeria.server.protobuf.ProtobufRequestConverterFunction.isJson;
import static com.linecorp.armeria.server.protobuf.ProtobufRequestConverterFunction.isProtobuf;
import static java.util.Objects.requireNonNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.reactivestreams.Publisher;

import com.google.common.collect.Streams;
import com.google.protobuf.Message;
import com.google.protobuf.MessageLite;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.Printer;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.streaming.JsonTextSequences;

/**
 * A {@link ResponseConverterFunction} which creates an {@link HttpResponse} with
 * {@code content-type: application/protobuf} or {@code content-type: application/json; charset=utf-8}.
 * If the returned object is an instance of {@link MessageLite}, the object can be converted to either
 * <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protocol Buffers</a> or
 * <a href="https://developers.google.com/protocol-buffers/docs/proto3#json">JSON</a> format.
 *
 * <h2>Conversion of multiple Protobuf messages</h2>
 * A sequence of Protocol Buffer messages can not be handled by this {@link ResponseConverterFunction},
 * because Protocol Buffers wire format is not self-delimiting.
 * See
 * <a href="https://developers.google.com/protocol-buffers/docs/techniques#streaming">Streaming Multiple Messages</a>
 * for more information.
 * However, {@link Publisher}, {@link Stream} and {@link Iterable} are supported when converting to
 * <a href="https://datatracker.ietf.org/doc/html/rfc7159#section-5">JSON array</a>.
 * <a href="https://datatracker.ietf.org/doc/rfc7464/">JavaScript Object Notation (JSON) Text Sequences</a>
 * is also supported for {@link Publisher}, {@link Stream}.
 *
 * <p>Note that this {@link ResponseConverterFunction} is applied to an annotated service by default,
 * so you don't have to specify this converter explicitly unless you want to use your own {@link Printer}.
 * The {@link JsonFormat#printer()} is used by default to format the response content.
 */
@UnstableApi
public final class ProtobufResponseConverterFunction implements ResponseConverterFunction {

    private static final MethodHandle fromPublisherMH;
    private static final MethodHandle fromStreamMH;
    private static final MethodHandle fromObjectMH;

    static {
        MethodHandle fromPublisher;
        try {
            final Method method = JsonTextSequences.class.getDeclaredMethod(
                    "fromPublisher", ResponseHeaders.class, Publisher.class,
                    HttpHeaders.class, Function.class);
            method.setAccessible(true);
            fromPublisher = MethodHandles.lookup().unreflect(method);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            // Should never reach here.
            fromPublisher = null;
        }
        fromPublisherMH = fromPublisher;

        MethodHandle fromStream;
        try {
            final Method method = JsonTextSequences.class.getDeclaredMethod(
                    "fromStream", ResponseHeaders.class, Stream.class,
                    HttpHeaders.class, Executor.class, Function.class);
            method.setAccessible(true);
            fromStream = MethodHandles.lookup().unreflect(method);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            // Should never reach here.
            fromStream = null;
        }
        fromStreamMH = fromStream;

        MethodHandle fromObject;
        try {
            final Method method = JsonTextSequences.class.getDeclaredMethod(
                    "fromObject", ResponseHeaders.class, Object.class, HttpHeaders.class, Function.class);
            method.setAccessible(true);
            fromObject = MethodHandles.lookup().unreflect(method);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            // Should never reach here.
            fromObject = null;
        }
        fromObjectMH = fromObject;
    }

    static final MediaType X_PROTOBUF = MediaType.create("application", "x-protobuf");
    // TODO(ikhoon): Add .omittingInsignificantWhitespace() for the sensible default when 2.0 is released?
    private static final Printer defaultJsonPrinter = JsonFormat.printer();

    private final Printer jsonPrinter;

    /**
     * Creates an instance with the default {@link Printer}.
     */
    public ProtobufResponseConverterFunction() {
        this(defaultJsonPrinter);
    }

    /**
     * Creates an instance with the specified {@link Printer}.
     */
    public ProtobufResponseConverterFunction(Printer jsonPrinter) {
        this.jsonPrinter = requireNonNull(jsonPrinter, "jsonPrinter");
    }

    @Override
    public Boolean isResponseStreaming(Type returnType, @Nullable MediaType produceType) {
        final Class<?> clazz = typeToClass(unwrapUnaryAsyncType(returnType));
        if (clazz == null) {
            return null;
        }

        // Non-streaming types.
        if (isJson(produceType)) {
            return false;
        }
        if (Message.class.isAssignableFrom(clazz) && isProtobuf(produceType)) {
            return false;
        }

        // Streaming types
        if (isJsonSeq(produceType) &&
            (Publisher.class.isAssignableFrom(clazz) || Stream.class.isAssignableFrom(clazz))) {
            return true;
        }

        return null;
    }

    @Override
    public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                        @Nullable Object result, HttpHeaders trailers) throws Exception {
        final MediaType contentType = headers.contentType();
        final boolean isJson = isJson(contentType);

        if (isJsonSeq(contentType)) {
            checkArgument(result != null, "a null value is not allowed for %s", contentType);
            final Function<Object, String> toJson = this::toJson;
            if (result instanceof Publisher) {
                @SuppressWarnings("unchecked")
                final Publisher<Object> publisher = (Publisher<Object>) result;
                try {
                    return (HttpResponse) fromPublisherMH.invoke(headers, publisher, trailers, toJson);
                } catch (Throwable ex) {
                    throw new IllegalStateException(
                            "Failed to call JsonTextSequences.fromPublisher() through reflection", ex);
                }
            }
            if (result instanceof Stream) {
                @SuppressWarnings("unchecked")
                final Stream<Object> stream = (Stream<Object>) result;
                try {
                    return (HttpResponse) fromStreamMH
                            .invoke(headers, stream, trailers, ctx.blockingTaskExecutor(), toJson);
                } catch (Throwable ex) {
                    throw new IllegalStateException(
                            "Failed to call JsonTextSequences.fromStream() through reflection", ex);
                }
            }
            try {
                return (HttpResponse) fromObjectMH.invoke(headers, result, trailers, toJson);
            } catch (Throwable ex) {
                throw new IllegalStateException(
                        "Failed to call JsonTextSequences.fromObject() through reflection", ex);
            }
        }

        if (result instanceof Message) {
            if (isJson) {
                final Charset charset = contentType.charset(StandardCharsets.UTF_8);
                return HttpResponse.of(headers, toJsonHttpData(result, charset), trailers);
            }

            if (contentType == null) {
                return HttpResponse.of(headers.toBuilder().contentType(MediaType.PROTOBUF).build(),
                                       toProtobuf(result), trailers);
            } else if (isProtobuf(contentType)) {
                return HttpResponse.of(headers, toProtobuf(result), trailers);
            } else {
                return ResponseConverterFunction.fallthrough();
            }
        }

        if (isJson) {
            checkArgument(result != null, "a null value is not allowed for %s", contentType);
            final Charset charset = contentType.charset(StandardCharsets.UTF_8);

            if (result instanceof Publisher) {
                @SuppressWarnings("unchecked")
                final Publisher<Object> publisher = (Publisher<Object>) result;
                return aggregateFrom(publisher, headers, trailers, obj -> toJsonHttpData(obj, charset), ctx);
            }
            if (result instanceof Stream) {
                @SuppressWarnings("unchecked")
                final Stream<Object> stream = (Stream<Object>) result;
                return aggregateFrom(stream, headers, trailers, obj -> toJsonHttpData(obj, charset),
                                     ctx.blockingTaskExecutor());
            }
            return HttpResponse.of(headers, toJsonHttpData(result, charset), trailers);
        } else {
            throw new IllegalArgumentException(
                    "Cannot convert a " + result + " to Protocol Buffers wire format");
        }
    }

    private static HttpData toProtobuf(Object message) {
        if (!(message instanceof Message)) {
            throw new IllegalStateException(
                    "Unexpected message type : " + message.getClass() + " (expected: a subtype of " +
                    Message.class.getName() + ')');
        }

        try {
            final Message cast = (Message) message;
            return HttpData.wrap(cast.toByteArray());
        } catch (Exception e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    private HttpData toJsonHttpData(Object message, Charset charset) {
        return HttpData.of(charset, toJson(message));
    }

    private String toJson(Object message) {
        if (message instanceof Iterable) {
            return Streams.stream((Iterable<?>) message)
                          .map(this::toJson)
                          .collect(Collectors.joining(",", "[", "]"));
        }

        if (message instanceof Map) {
            final Map<?, ?> map = (Map<?, ?>) message;
            return map.entrySet()
                      .stream()
                      .map(entry -> '"' + entry.getKey().toString() + "\":" + toJson(entry.getValue()))
                      .collect(Collectors.joining(",", "{", "}"));
        }

        if (!(message instanceof Message)) {
            throw new IllegalStateException(
                    "Unexpected message type : " + message.getClass() + " (expected: a subtype of " +
                    Message.class.getName() + ')');
        }

        try {
            //noinspection OverlyStrongTypeCast
            return jsonPrinter.print((Message) message);
        } catch (Exception e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    private static boolean isJsonSeq(@Nullable MediaType mediaType) {
        return mediaType != null && mediaType.is(MediaType.JSON_SEQ);
    }
}
