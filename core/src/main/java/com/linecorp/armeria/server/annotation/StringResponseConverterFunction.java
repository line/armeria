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

import static com.linecorp.armeria.internal.server.ResponseConversionUtil.aggregateFrom;

import java.nio.charset.Charset;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A {@link ResponseConverterFunction} which creates an {@link HttpResponse} when:
 * <ul>
 *   <li>the {@code result} is an instance of {@link CharSequence}</li>
 *   <li>the {@code "content-type"} of the {@link ResponseHeaders} is {@link MediaType#ANY_TEXT_TYPE}</li>
 * </ul>
 * Note that this {@link ResponseConverterFunction} is applied to an annotated service by default,
 * so you don't have to specify this converter explicitly.
 */
public final class StringResponseConverterFunction implements ResponseConverterFunction {

    @Override
    public HttpResponse convertResponse(ServiceRequestContext ctx,
                                        ResponseHeaders headers,
                                        @Nullable Object result,
                                        HttpHeaders trailers) throws Exception {
        final MediaType mediaType = headers.contentType();
        if (mediaType != null) {
            // @Produces("text/plain") or @ProducesText is specified.
            if (mediaType.is(MediaType.ANY_TEXT_TYPE)) {
                // Use 'utf-8' charset by default.
                final Charset charset = mediaType.charset(ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET);

                // To avoid sending an unfinished text to the client, always aggregate the published strings.
                if (result instanceof Publisher) {
                    return aggregateFrom((Publisher<?>) result, headers, trailers, o -> toHttpData(o, charset));
                }
                if (result instanceof Stream) {
                    return aggregateFrom((Stream<?>) result, headers, trailers,
                                         o -> toHttpData(o, charset), ctx.blockingTaskExecutor());
                }
                return HttpResponse.of(headers, toHttpData(result, charset), trailers);
            }
        } else if (result instanceof CharSequence) {
            return HttpResponse.of(headers.toBuilder().contentType(MediaType.PLAIN_TEXT_UTF_8).build(),
                                   HttpData.ofUtf8(((CharSequence) result).toString()),
                                   trailers);
        }

        return ResponseConverterFunction.fallthrough();
    }

    private static HttpData toHttpData(@Nullable Object value, Charset charset) {
        if (value == null) {
            // To prevent to convert null value to 'null' string.
            return HttpData.empty();
        }

        if (value instanceof Iterable) {
            final StringBuilder sb = new StringBuilder();
            ((Iterable<?>) value).forEach(v -> {
                // TODO(trustin): Inefficient double conversion. Time to write HttpDataBuilder?
                if (v instanceof HttpData) {
                    sb.append(((HttpData) v).toString(charset));
                } else if (v instanceof byte[]) {
                    sb.append(new String((byte[]) v, charset));
                } else {
                    sb.append(v);
                }
            });
            return HttpData.of(charset, sb);
        }

        if (value instanceof HttpData) {
            return (HttpData) value;
        }

        if (value instanceof byte[]) {
            return HttpData.wrap((byte[]) value);
        }

        return HttpData.of(charset, String.valueOf(value));
    }
}
