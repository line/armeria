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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A {@link RequestConverterFunction} which converts a binary body of the
 * {@link AggregatedHttpRequest} to one of {@code byte[]} or {@link HttpData} depending on the
 * {@code expectedResultType}.
 * Note that this {@link RequestConverterFunction} is applied to an annotated service by default,
 * so you don't have to specify this converter explicitly.
 */
public final class ByteArrayRequestConverterFunction implements RequestConverterFunction {

    @Override
    public Object convertRequest(
            ServiceRequestContext ctx, AggregatedHttpRequest request, Class<?> expectedResultType,
            @Nullable ParameterizedType expectedParameterizedResultType) throws Exception {

        final HttpData content = request.content();
        if (expectedResultType == byte[].class) {
            return content.array();
        }
        if (expectedResultType == HttpData.class) {
            return content;
        }
        return RequestConverterFunction.fallthrough();
    }
}
