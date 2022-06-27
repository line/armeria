/*
 * Copyright 2022 LINE Corporation
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

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.FallthroughException;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

/**
 * A {@link ResponseConverterFunction} which takes in two {@link ResponseConverterFunction}s
 * Upon the call to convert the response, attempt to use the first function.
 * If the first function is unable to convert the response, fall through to use the second function.
 */
public final class OrElseResponseConverterFunction implements ResponseConverterFunction {

    private final ResponseConverterFunction firstConverter;
    private final ResponseConverterFunction secondConverter;

    public OrElseResponseConverterFunction(ResponseConverterFunction firstConverter,
                                           ResponseConverterFunction secondConverter) {
        this.firstConverter = firstConverter;
        this.secondConverter = secondConverter;
    }

    @Override
    public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                        @Nullable Object result, HttpHeaders trailers) throws Exception {
        try {
            return firstConverter.convertResponse(ctx, headers, result, trailers);
        } catch (FallthroughException ignored) {
            return secondConverter.convertResponse(ctx, headers, result, trailers);
        }
    }
}
