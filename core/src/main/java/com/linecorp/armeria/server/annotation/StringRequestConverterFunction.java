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

import java.lang.reflect.ParameterizedType;
import java.nio.charset.Charset;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A {@link RequestConverterFunction} which converts a text body of the
 * {@link AggregatedHttpRequest} to a {@link String}.
 * Note that this {@link RequestConverterFunction} is applied to the annotated service by default,
 * so you don't have to set explicitly.
 */
public final class StringRequestConverterFunction implements RequestConverterFunction {
    /**
     * Converts the specified {@link AggregatedHttpRequest} to a {@link String}.
     */
    @Override
    public Object convertRequest(
            ServiceRequestContext ctx, AggregatedHttpRequest request, Class<?> expectedResultType,
            @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {

        if (expectedResultType == String.class ||
            expectedResultType == CharSequence.class) {
            final Charset charset;
            final MediaType contentType = request.contentType();
            if (contentType != null) {
                charset = contentType.charset(ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET);
            } else {
                charset = ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET;
            }
            return request.content(charset);
        }
        return RequestConverterFunction.fallthrough();
    }
}
