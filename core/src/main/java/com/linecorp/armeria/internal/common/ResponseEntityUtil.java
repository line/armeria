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

package com.linecorp.armeria.internal.common;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Utilities for {@link ResponseEntity}.
 */
public final class ResponseEntityUtil {
    /**
     * Build {@link ResponseHeaders} from the given {@link ServiceRequestContext} and {@link ResponseEntity}.
     */
    public static ResponseHeaders buildResponseHeaders(ServiceRequestContext ctx, ResponseEntity<?> result) {
        final ResponseHeadersBuilder builder = result.headers().toBuilder();

        if (builder.status().isContentAlwaysEmpty()) {
            return builder.build();
        }
        if (builder.contentType() != null) {
            return builder.build();
        }

        final MediaType negotiatedResponseMediaType = ctx.negotiatedResponseMediaType();
        if (negotiatedResponseMediaType != null) {
            builder.contentType(negotiatedResponseMediaType);
        }

        return builder.build();
    }

    private ResponseEntityUtil() {}
}
