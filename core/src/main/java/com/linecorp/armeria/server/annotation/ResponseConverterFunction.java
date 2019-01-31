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
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.FallthroughException;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AsciiString;

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
     * @param headers The HTTP headers that you might want to use to create the {@link HttpResponse}.
     *                The status of headers is {@link HttpStatus#OK} by default or
     *                {@link HttpStatus#NO_CONTENT} if the annotated method returns {@code void},
     *                unless you specify it with {@link StatusCode} on the method.
     *                The headers also will include a {@link MediaType} if
     *                {@link ServiceRequestContext#negotiatedResponseMediaType()} returns it.
     *                If the method returns {@link HttpResult}, this headers is the same headers from
     *                {@link HttpResult#headers()}
     *                The headers could be immutable, so please call {@link HttpHeaders#toMutable()}.
     *                Please note that the additional headers set by
     *                {@link ServiceRequestContext#addAdditionalResponseHeader(AsciiString, String)}
     *                and {@link AdditionalHeader} are not included in this headers.
     * @param result The result of the service method.
     * @param trailingHeaders The HTTP trailers that you might want to use to create the {@link HttpResponse}.
     *                        If the annotated method returns {@link HttpResult}, this trailers is the same
     *                        trailers from {@link HttpResult#trailingHeaders()}.
     *                        The trailers could be immutable, so please call {@link HttpHeaders#toMutable()}.
     *                        Please note that the additional trailers set by
     *                        {@link ServiceRequestContext#addAdditionalResponseTrailer(AsciiString, String)}
     *                        and {@link AdditionalTrailer} are not included in this trailers.
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
