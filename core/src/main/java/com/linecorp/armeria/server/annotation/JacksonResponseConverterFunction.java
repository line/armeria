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

import static com.linecorp.armeria.internal.annotation.ResponseConversionUtil.aggregateFrom;
import static com.linecorp.armeria.internal.annotation.ResponseConversionUtil.streamingFrom;
import static com.linecorp.armeria.internal.annotation.ResponseConversionUtil.toMutableHeaders;
import static java.util.Objects.requireNonNull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A response converter implementation which creates an {@link HttpResponse} with
 * {@code content-type: application/json; charset=utf-8} or {@code content-type: application/json-seq}.
 * The objects published from a {@link Publisher} or {@link Stream} would be converted into JSON Text Sequences
 * if a {@link ProducesJsonSequences} annotation is specified on an annotated service method.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7464">JavaScript Object Notation (JSON) Text Sequences</a>
 */
public class JacksonResponseConverterFunction implements ResponseConverterFunction {

    private static final ObjectMapper defaultObjectMapper = new ObjectMapper();

    private final ObjectMapper mapper;

    /**
     * A record separator which indicates the beginning of a JSON text.
     */
    @VisibleForTesting
    static final byte RECORD_SEPARATOR = 0x1E;

    /**
     * A line feed which indicates the end of a JSON text.
     */
    @VisibleForTesting
    static final byte LINE_FEED = 0x0A;

    /**
     * Creates an instance with the default {@link ObjectMapper}.
     */
    public JacksonResponseConverterFunction() {
        this(defaultObjectMapper);
    }

    /**
     * Creates an instance with the specified {@link ObjectMapper}.
     */
    public JacksonResponseConverterFunction(ObjectMapper mapper) {
        this.mapper = requireNonNull(mapper, "mapper");
    }

    @Override
    public HttpResponse convertResponse(ServiceRequestContext ctx,
                                        HttpHeaders headers,
                                        @Nullable Object result,
                                        HttpHeaders trailingHeaders) throws Exception {
        final MediaType mediaType = headers.contentType();
        if (mediaType != null) {
            // @Produces("application/json") or @ProducesJson is specified.
            // Any MIME type which ends with '+json' such as 'application/json-patch+json' can be also accepted.
            if (mediaType.is(MediaType.JSON) || mediaType.subtype().endsWith("+json")) {
                final Charset charset = mediaType.charset().orElse(StandardCharsets.UTF_8);
                // Convert the object only if the charset supports UTF-8,
                // because ObjectMapper always writes JSON document as UTF-8.
                if (charset.contains(StandardCharsets.UTF_8)) {
                    if (result instanceof Publisher) {
                        return aggregateFrom((Publisher<?>) result, headers, trailingHeaders,
                                             this::toJsonHttpData);
                    }
                    if (result instanceof Stream) {
                        return aggregateFrom((Stream<?>) result, headers, trailingHeaders,
                                             this::toJsonHttpData, ctx.blockingTaskExecutor());
                    }
                    return HttpResponse.of(headers, toJsonHttpData(result), trailingHeaders);
                }
            }

            // @Produces("application/json-seq") or @ProducesJsonSequences is specified.
            if (mediaType.is(MediaType.JSON_SEQ)) {
                if (result instanceof Publisher) {
                    return streamingFrom((Publisher<?>) result, headers, trailingHeaders,
                                         this::toJsonSequencesHttpData);
                }
                if (result instanceof Stream) {
                    return streamingFrom((Stream<?>) result, headers, trailingHeaders,
                                         this::toJsonSequencesHttpData, ctx.blockingTaskExecutor());
                }
                return HttpResponse.of(headers, toJsonSequencesHttpData(result), trailingHeaders);
            }
        } else if (result instanceof JsonNode) {
            // No media type is specified, but the result is a JsonNode type.
            return HttpResponse.of(toMutableHeaders(headers).contentType(MediaType.JSON_UTF_8),
                                   toJsonHttpData(result), trailingHeaders);
        }

        return ResponseConverterFunction.fallthrough();
    }

    private HttpData toJsonHttpData(@Nullable Object value) {
        try {
            return HttpData.of(mapper.writeValueAsBytes(value));
        } catch (Exception e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    private HttpData toJsonSequencesHttpData(@Nullable Object value) {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(RECORD_SEPARATOR);
            mapper.writeValue(out, value);
            out.write(LINE_FEED);
            return HttpData.of(out.toByteArray());
        } catch (Exception e) {
            return Exceptions.throwUnsafely(e);
        }
    }
}
