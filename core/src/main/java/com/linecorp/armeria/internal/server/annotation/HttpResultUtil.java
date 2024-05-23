/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.internal.server.annotation;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.AnnotatedService;
import com.linecorp.armeria.server.annotation.HttpResult;

final class HttpResultUtil {
    static ResponseHeaders buildResponseHeaders(ServiceRequestContext ctx, HttpResult<?> result) {
        final ResponseHeadersBuilder builder;
        final HttpHeaders customHeaders = result.headers();

        // Prefer ResponseHeaders#toBuilder because builder#add(Iterable) is an expensive operation.
        if (customHeaders instanceof ResponseHeaders) {
            builder = ((ResponseHeaders) customHeaders).toBuilder();
        } else {
            builder = ResponseHeaders.builder();
            builder.add(customHeaders);

            if (!builder.contains(HttpHeaderNames.STATUS)) {
                final AnnotatedService service = ctx.config().service().as(AnnotatedService.class);
                if (service != null) {
                    builder.status(service.defaultStatus());
                } else {
                    builder.status(HttpStatus.OK);
                }
            }
        }

        return maybeAddContentType(ctx, builder).build();
    }

    private static ResponseHeadersBuilder maybeAddContentType(ServiceRequestContext ctx,
                                                              ResponseHeadersBuilder builder) {
        if (builder.status().isContentAlwaysEmpty()) {
            return builder;
        }
        if (builder.contentType() != null) {
            return builder;
        }

        final MediaType negotiatedResponseMediaType = ctx.negotiatedResponseMediaType();
        if (negotiatedResponseMediaType != null) {
            builder.contentType(negotiatedResponseMediaType);
        }

        return builder;
    }

    private HttpResultUtil() {}
}
