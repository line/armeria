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

import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A response converter implementation which creates an {@link HttpResponse} with
 * {@code content-type: application/json; charset=utf-8}.
 */
public class JacksonResponseConverterFunction implements ResponseConverterFunction {

    private static final ObjectMapper defaultObjectMapper = new ObjectMapper();

    private final ObjectMapper mapper;

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
                                        @Nullable HttpHeaders trailingHeaders) throws Exception {
        final MediaType mediaType = headers.contentType();
        if (mediaType != null) {
            // @Produces("application/json") or @ProducesJson is specified.
            // Any MIME type which ends with '+json' such as 'application/json-patch+json' can be also accepted.
            if (mediaType.is(MediaType.JSON) || mediaType.subtype().endsWith("+json")) {
                final Charset charset = mediaType.charset().orElse(StandardCharsets.UTF_8);

                // Convert the object only if the charset supports UTF-8,
                // because ObjectMapper always writes JSON document as UTF-8.
                if (charset.contains(StandardCharsets.UTF_8)) {
                    final HttpData body = HttpData.of(mapper.writeValueAsBytes(result));
                    return HttpResponse.of(headers, body, Optional.ofNullable(trailingHeaders));
                }
            }
        } else if (result instanceof JsonNode) {
            // No media type is specified, but the result is a JsonNode type.
            final HttpHeaders responseHeaders =
                    headers.isImmutable() ? HttpHeaders.copyOf(headers).contentType(MediaType.JSON_UTF_8)
                                          : headers.contentType(MediaType.JSON_UTF_8);
            final HttpData body = HttpData.of(mapper.writeValueAsBytes(result));
            return HttpResponse.of(responseHeaders, body, Optional.ofNullable(trailingHeaders));
        }

        return ResponseConverterFunction.fallthrough();
    }
}
