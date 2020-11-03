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
import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;

import com.google.common.collect.Streams;
import com.google.protobuf.MessageLite;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.Printer;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

/**
 * A {@link ResponseConverterFunction} which creates an {@link HttpResponse} with
 * {@code content-type: application/protobuf} or {@code content-type: application/json; charset=utf-8}.
 * If the returned object is instance of {@link MessageLite}, the object can be converted to
 * <a href="https://developers.google.com/protocol-buffers/docs/encoding">Protocol Buffer</a> or
 * <a href="https://developers.google.com/protocol-buffers/docs/proto3#json">JSON</a> format.
 *
 * <p>The Protocol Buffers spec does not have an official way to sending multiple messages because
 * an encoded message does not have self-delimiting.
 * See <a href="https://developers.google.com/protocol-buffers/docs/techniques#streaming">Streaming Multiple Messages</a>
 * for more information.
 * Therefore if the returned object is instance of {@link Publisher}, {@link Stream} or {@link Iterable}
 * which produces {@link MessageLite}s, the object is only able to convert to
 * <a href="https://tools.ietf.org/html/rfc7159#section-5">JSON array</a> format.
 *
 * <p>Note that this {@link ResponseConverterFunction} is applied to the annotated service by default,
 * so you don't have to set explicitly unless you want to use your own {@link Printer}.
 */
@UnstableApi
public final class ProtobufResponseConverterFunction implements ResponseConverterFunction {

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
    public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                        @Nullable Object result, HttpHeaders trailers) throws Exception {
        final MediaType mediaType = headers.contentType();
        if (result instanceof MessageLite) {
            if (mediaType != null) {
                if (mediaType.is(MediaType.JSON) || mediaType.subtype().endsWith("+json")) {
                    final Charset charset = mediaType.charset(StandardCharsets.UTF_8);
                    if (charset.contains(StandardCharsets.UTF_8)) {
                        return HttpResponse.of(headers, toJsonHttpData(result), trailers);
                    }
                }
                return HttpResponse.of(headers, toProtobuf(result), trailers);
            }

            return HttpResponse.of(headers.toBuilder().contentType(MediaType.PROTOBUF).build(),
                                   toProtobuf(result), trailers);
        }

        if (mediaType != null) {
            final String subtype = mediaType.subtype();
            if (subtype.contains("protobuf")) {
                checkArgument(result != null, "a null value is not allowed for %s", mediaType);
                final Charset charset = mediaType.charset(StandardCharsets.UTF_8);
                final boolean isJson = subtype.contains("+json") && charset.contains(StandardCharsets.UTF_8);

                if (isJson) {
                    if (result instanceof Publisher) {
                        @SuppressWarnings("unchecked")
                        final Publisher<Object> publisher = (Publisher<Object>) result;
                        return aggregateFrom(publisher, headers, trailers, this::toJsonHttpData);
                    }
                    if (result instanceof Stream) {
                        @SuppressWarnings("unchecked")
                        final Stream<Object> stream = (Stream<Object>) result;
                        return aggregateFrom(stream, headers, trailers, this::toJsonHttpData,
                                             ctx.blockingTaskExecutor());
                    }
                    return HttpResponse.of(headers, toJsonHttpData(result), trailers);
                }

                return HttpResponse.of(headers, toProtobuf(result), trailers);
            }
        }

        return ResponseConverterFunction.fallthrough();
    }

    private static HttpData toProtobuf(Object message) {
        if (!(message instanceof MessageLite)) {
            throw new IllegalStateException(
                    "Unexpected message type : " + message.getClass() + " (expected: a subtype of " +
                    MessageLite.class.getName() + ')');
        }

        try {
            return HttpData.wrap(((MessageLite) message).toByteArray());
        } catch (Exception e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    private HttpData toJsonHttpData(Object message) {
        return HttpData.ofUtf8(toJson(message));
    }

    private String toJson(Object message) {
        if (message instanceof Iterable) {
            return Streams.stream((Iterable<?>) message)
                          .map(this::toJson)
                          .collect(Collectors.joining(",", "[", "]"));
        }

        if (!(message instanceof MessageOrBuilder)) {
            throw new IllegalStateException(
                    "Unexpected message type : " + message.getClass() + " (expected: a subtype of " +
                    MessageOrBuilder.class.getName() + ')');
        }

        try {
            return jsonPrinter.print((MessageOrBuilder) message);
        } catch (Exception e) {
            return Exceptions.throwUnsafely(e);
        }
    }
}
