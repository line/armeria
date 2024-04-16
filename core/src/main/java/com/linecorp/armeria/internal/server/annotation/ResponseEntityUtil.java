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

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Utilities for {@link ResponseEntity}.
 */
final class ResponseEntityUtil {
    /**
     * Build {@link ResponseHeaders} from the given {@link ServiceRequestContext} and {@link ResponseEntity}.
     */
    static ResponseHeaders buildResponseHeaders(ServiceRequestContext ctx, ResponseEntity<?> result) {
        final ResponseHeaders headers = result.headers();

        if (headers.status().isContentAlwaysEmpty()) {
            return headers;
        }
        if (headers.contentType() != null) {
            return headers;
        }

        final ResponseHeadersBuilder builder = headers.toBuilder();

        final MediaType negotiatedResponseMediaType = ctx.negotiatedResponseMediaType();
        if (negotiatedResponseMediaType != null) {
            builder.contentType(negotiatedResponseMediaType);
        }

        return builder.build();
    }

    private ResponseEntityUtil() {}
}
