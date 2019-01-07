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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A response converter implementation which creates an {@link HttpResponse} with
 * {@code content-type: application/binary} or {@code content-type: application/octet-stream}.
 */
public class ByteArrayResponseConverterFunction implements ResponseConverterFunction {

    @Override
    public HttpResponse convertResponse(ServiceRequestContext ctx,
                                        HttpHeaders headers,
                                        @Nullable Object result,
                                        HttpHeaders trailingHeaders) throws Exception {
        if (result instanceof HttpData) {
            return response(headers, ((HttpData) result).array(), trailingHeaders);
        }
        if (result instanceof byte[]) {
            return response(headers, (byte[]) result, trailingHeaders);
        }
        return ResponseConverterFunction.fallthrough();
    }

    private static HttpResponse response(HttpHeaders headers, byte[] content,
                                         HttpHeaders trailingHeaders) {
        final MediaType contentType = headers.contentType();
        if (contentType == null) {
            final HttpHeaders responseHeaders = headers.isImmutable() ? HttpHeaders.copyOf(headers)
                                                                      : headers;
            final HttpData responseContent = HttpData.of(content);
            return HttpResponse.of(responseHeaders.contentType(MediaType.OCTET_STREAM),
                                   responseContent, trailingHeaders);
        }

        // A user expects 'binary'.
        if (contentType.is(MediaType.APPLICATION_BINARY) ||
            contentType.is(MediaType.OCTET_STREAM)) {
            // @Produces("application/binary") or @ProducesBinary
            // @Produces("application/octet-stream") or @ProducesOctetStream
            final HttpData responseContent = HttpData.of(content);
            return HttpResponse.of(headers, responseContent, trailingHeaders);
        }

        return ResponseConverterFunction.fallthrough();
    }
}
