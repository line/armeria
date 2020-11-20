/*
 * Copyright 2018 LINE Corporation
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

import static com.linecorp.armeria.internal.server.ResponseConversionUtil.streamingFrom;

import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A {@link ResponseConverterFunction} which creates an {@link HttpResponse} when:
 * <ul>
 *   <li>the {@code result} is an instance of {@link HttpData} or {@code byte[]}</li>
 *   <li>the {@code result} is an instance of {@link Publisher} or {@link Stream} while the
 *       {@code "content-type"} of the {@link ResponseHeaders} is {@code "application/binary"} or
 *       {@code "application/octet-stream"}</li>
 * </ul>
 * Note that this {@link ResponseConverterFunction} is applied to an annotated service by default,
 * so you don't have to specify this converter explicitly.
 */
public final class ByteArrayResponseConverterFunction implements ResponseConverterFunction {

    @Override
    public HttpResponse convertResponse(ServiceRequestContext ctx,
                                        ResponseHeaders headers,
                                        @Nullable Object result,
                                        HttpHeaders trailers) throws Exception {
        final MediaType contentType = headers.contentType();
        if (contentType != null) {
            // @Produces("application/binary") or @ProducesBinary
            // @Produces("application/octet-stream") or @ProducesOctetStream
            if (contentType.is(MediaType.APPLICATION_BINARY) ||
                contentType.is(MediaType.OCTET_STREAM)) {
                // We assume that the publisher and stream produces HttpData or byte[].
                // An IllegalStateException will be raised for other types due to conversion failure.
                if (result instanceof Publisher) {
                    return streamingFrom((Publisher<?>) result, headers, trailers,
                                         ByteArrayResponseConverterFunction::toHttpData);
                }
                if (result instanceof Stream) {
                    return streamingFrom((Stream<?>) result, headers, trailers,
                                         ByteArrayResponseConverterFunction::toHttpData,
                                         ctx.blockingTaskExecutor());
                }
            }

            if (result instanceof HttpData) {
                return HttpResponse.of(headers, (HttpData) result, trailers);
            }

            if (result instanceof byte[]) {
                return HttpResponse.of(headers, HttpData.wrap((byte[]) result), trailers);
            }

            return ResponseConverterFunction.fallthrough();
        }

        if (result instanceof HttpData) {
            return HttpResponse.of(headers.toBuilder().contentType(MediaType.OCTET_STREAM).build(),
                                   (HttpData) result, trailers);
        }

        if (result instanceof byte[]) {
            return HttpResponse.of(headers.toBuilder().contentType(MediaType.OCTET_STREAM).build(),
                                   HttpData.wrap((byte[]) result), trailers);
        }

        return ResponseConverterFunction.fallthrough();
    }

    private static HttpData toHttpData(@Nullable Object value) {
        if (value instanceof HttpData) {
            return (HttpData) value;
        }
        if (value instanceof byte[]) {
            return HttpData.wrap((byte[]) value);
        }
        if (value == null) {
            return HttpData.empty();
        }
        throw new IllegalStateException("Failed to convert an object to an HttpData: " +
                                        value.getClass().getName());
    }
}
