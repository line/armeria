/*
 * Copyright 2020 LINE Corporation
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

import java.lang.reflect.ParameterizedType;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.FallthroughException;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;

/**
 * A {@link RequestConverterFunction} which wraps a list of {@link RequestConverterFunction}s.
 */
final class CompositeRequestConverterFunction implements RequestConverterFunction {

    private final ImmutableList<RequestConverterFunction> functions;

    CompositeRequestConverterFunction(ImmutableList<RequestConverterFunction> functions) {
        this.functions = functions;
    }

    @Nullable
    @Override
    public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpRequest request,
                                 Class<?> expectedResultType,
                                 @Nullable ParameterizedType expectedParameterizedResultType)
            throws Exception {
        for (RequestConverterFunction function : functions) {
            try {
                return function.convertRequest(ctx, request, expectedResultType,
                                               expectedParameterizedResultType);
            } catch (FallthroughException ignore) {
                // Do nothing.
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Request converter " + function.getClass().getName() +
                        " failed to convert an " + request + " to a " + expectedResultType, e);
            }
        }
        return RequestConverterFunction.fallthrough();
    }
}
