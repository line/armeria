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

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Converts an {@link AggregatedHttpRequest} to an object. The class implementing this interface would
 * be specified as a value of a {@link RequestConverter} annotation.
 *
 * @see RequestConverter
 * @see RequestObject
 */
@FunctionalInterface
public interface RequestConverterFunction {

    /**
     * Converts the specified {@code request} to an object of {@code expectedResultType}.
     * Calls {@link RequestConverterFunction#fallthrough()} or throws a {@link FallthroughException} if
     * this converter cannot convert the {@code request} to an object.
     *
     * @param ctx the {@link ServiceRequestContext} of {@code request}.
     * @param request the {@link AggregatedHttpRequest} being handled.
     * @param expectedResultType the desired type of the conversion result.
     * @param expectedParameterizedResultType the desired parameterized type of the conversion result.
     *                                        {@code null} will be given if {@code expectedResultType} doesn't
     *                                        have any type parameters.
     */
    @Nullable
    Object convertRequest(ServiceRequestContext ctx, AggregatedHttpRequest request,
                          Class<?> expectedResultType,
                          @Nullable ParameterizedType expectedParameterizedResultType) throws Exception;

    /**
     * Throws a {@link FallthroughException} in order to try to convert the {@code request} to
     * an object by the next converter.
     */
    static <T> T fallthrough() {
        // Always throw the exception quietly.
        throw FallthroughException.get();
    }
}
