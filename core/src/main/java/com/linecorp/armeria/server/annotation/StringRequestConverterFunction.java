/*
 * Copyright 2017 LINE Corporation
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

import java.nio.charset.StandardCharsets;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A default implementation of a {@link RequestConverterFunction} which converts a text body of
 * the {@link AggregatedHttpMessage} to a {@link String}.
 */
public class StringRequestConverterFunction implements RequestConverterFunction {
    /**
     * Converts the specified {@link AggregatedHttpMessage} to a {@link String}.
     */
    @Override
    public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                 Class<?> expectedResultType) throws Exception {
        if (expectedResultType == String.class ||
            expectedResultType == CharSequence.class) {
            final MediaType contentType = request.contentType();
            if (contentType != null && contentType.is(MediaType.ANY_TEXT_TYPE)) {
                // See https://tools.ietf.org/html/rfc2616#section-3.7.1
                return request.content(contentType.charset().orElse(StandardCharsets.ISO_8859_1));
            }
        }
        return RequestConverterFunction.fallthrough();
    }
}
