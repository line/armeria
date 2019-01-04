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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A response converter implementation which creates an {@link HttpResponse} with
 * {@code content-type: text/plain}.
 */
public class StringResponseConverterFunction implements ResponseConverterFunction {

    @Override
    public HttpResponse convertResponse(ServiceRequestContext ctx,
                                        HttpHeaders headers,
                                        @Nullable Object result,
                                        HttpHeaders trailingHeaders) throws Exception {
        final MediaType mediaType = headers.contentType();
        if (mediaType != null) {
            // @Produces("text/plain") or @ProducesText is specified.
            if (mediaType.is(MediaType.ANY_TEXT_TYPE)) {
                // Use 'utf-8' charset by default.
                final Charset charset = mediaType.charset().orElse(StandardCharsets.UTF_8);
                final HttpData content = HttpData.of(String.valueOf(result).getBytes(charset));
                return HttpResponse.of(headers, content, trailingHeaders);
            }
        } else if (result instanceof CharSequence) {
            final HttpHeaders responseHeaders =
                    headers.isImmutable() ? HttpHeaders.copyOf(headers).contentType(MediaType.PLAIN_TEXT_UTF_8)
                                          : headers.contentType(MediaType.PLAIN_TEXT_UTF_8);
            final HttpData content = HttpData.ofUtf8(((CharSequence) result).toString());
            return HttpResponse.of(responseHeaders, content, trailingHeaders);
        }

        return ResponseConverterFunction.fallthrough();
    }
}
