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

import com.linecorp.armeria.common.HttpHeaders;
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
     *
     * @param headers The HTTP headers of the {@link HttpResult} returned by the service method.
     *                Even if the service method does not return any {@link HttpResult},
     *                the headers will basically include {@code "status"} and {@code "content-type"}
     *                HTTP Response Header.
     *                Note that the additional headers will not be included in this headers,
     *                call {@link ServiceRequestContext#additionalResponseHeaders()} to access them.
     * @param result The result of the service method.
     * @param trailingHeaders The HTTP trailers of {@link HttpResult} returned by the service method.
     *                        If the service method does not return any {@link HttpResult},
     *                        the trailers will be empty, not {@code null}.
     *                        Note that the additional trailers will not be included in this trailers,
     *                        call {@link ServiceRequestContext#additionalResponseTrailers()} to access them.
     */
    HttpResponse convertResponse(ServiceRequestContext ctx,
                                 HttpHeaders headers,
                                 @Nullable Object result,
                                 HttpHeaders trailingHeaders) throws Exception;

    /**
     * Throws a {@link FallthroughException} in order to try to convert {@code result} to
     * {@link HttpResponse} by the next converter.
     */
    static <T> T fallthrough() {
        // Always throw the exception quietly.
        throw FallthroughException.get();
    }
}
