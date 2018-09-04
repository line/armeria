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

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A response converter implementation which creates an {@link HttpResponse} with
 * {@code content-type: text/plain}.
 */
public class StringResponseConverterFunction implements ResponseConverterFunction {

    @Override
    public HttpResponse convertResponse(ServiceRequestContext ctx,
                                        @Nullable Object result) throws Exception {

        final MediaType mediaType = ctx.negotiatedResponseMediaType();
        if (mediaType != null) {
            // @Produces("text/plain") or @ProducesText is specified.
            if (mediaType.is(MediaType.ANY_TEXT_TYPE)) {
                // Use 'utf-8' charset by default.
                final Charset charset = mediaType.charset().orElse(StandardCharsets.UTF_8);
                return HttpResponse.of(HttpStatus.OK, mediaType.withCharset(charset),
                                       String.valueOf(result).getBytes(charset));
            }
        } else if (result instanceof CharSequence) {
            return HttpResponse.of(((CharSequence) result).toString());
        }

        return ResponseConverterFunction.fallthrough();
    }
}
