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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.FallthroughException;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Converts a {@code result} object to {@link HttpResponse}. The class implementing this interface would
 * be specified as {@link ResponseConverter} annotation.
 *
 * @see ResponseConverter
 */
@FunctionalInterface
public interface ResponseConverterFunction {

    /**
     * Returns {@link HttpResponse} instance corresponds to the given {@code result}.
     * Calls {@link ResponseConverterFunction#fallthrough()} or throws a {@link FallthroughException} if
     * this converter cannot convert the {@code result} to the {@link HttpResponse}.
     */
    HttpResponse convertResponse(ServiceRequestContext ctx, @Nullable Object result) throws Exception;

    /**
     * Throws a {@link FallthroughException} in order to try to convert {@code result} to
     * {@link HttpResponse} by the next converter.
     */
    static <T> T fallthrough() {
        // Always throw the exception quietly.
        throw FallthroughException.get();
    }
}
