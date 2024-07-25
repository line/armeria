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

import java.lang.reflect.Type;
import java.util.function.Consumer;

import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
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
     * Returns whether an {@link HttpResponse} of an annotated service should be streamed.
     * {@code null} if this converter cannot convert the {@code responseType} to an {@link HttpResponse}.
     * {@code true} can be returned if the response is a streaming type, or the
     * {@link ResponseConverterFunction} has not been optimized for the {@code responseType} through overriding
     * this method for backward compatibility.
     *
     * <p>This method is used as a performance optimization hint.
     * If the {@code returnType} and {@code produceType} are not a streaming response,
     * it is recommended to return {@code false} for the better performance.
     *
     * <p>Note that you should never return {@code false} for a streaming response.
     * The non-streaming response is aggregated before being sent.
     *
     * @param returnType the return type of the annotated service.
     * @param produceType the negotiated producible media type of the annotated service.
     *                    {@code null} if the media type negotiation is not used for the service.
     */
    @UnstableApi
    @Nullable
    default Boolean isResponseStreaming(Type returnType, @Nullable MediaType produceType) {
        // TODO(ikhoon): Make this method an abstract method in Armeria 2.0 so that users always implement
        //               this method to explicitly set whether to support response streaming for
        //               the `returnType`.
        return true;
    }

    /**
     * Returns {@link HttpResponse} instance corresponds to the given {@code result}.
     * Calls {@link ResponseConverterFunction#fallthrough()} if
     * this converter cannot convert the {@code result} to the {@link HttpResponse}.
     *
     * @param headers The HTTP headers that you might want to use to create the {@link HttpResponse}.
     *                The status of headers is {@link HttpStatus#OK} by default or
     *                {@link HttpStatus#NO_CONTENT} if the annotated method returns {@code void},
     *                unless you specify it with {@link StatusCode} on the method.
     *                The headers also will include a {@link MediaType} if
     *                {@link ServiceRequestContext#negotiatedResponseMediaType()} returns it.
     *                If the annotated method returns an {@link HttpResult} or a {@link ResponseEntity},
     *                the headers provided by them will be given as they are.
     *                Please note that the additional headers set by
     *                {@link ServiceRequestContext#mutateAdditionalResponseHeaders(Consumer)}
     *                and {@link AdditionalHeader} are not included in this headers.
     * @param result The result of the service method.
     * @param trailers The HTTP trailers that you might want to use to create the {@link HttpResponse}.
     *                 If the annotated method returns an {@link HttpResult} or a {@link ResponseEntity},
     *                 the trailers provided by them will be given as they are.
     *                 Please note that the additional trailers set by
     *                 {@link ServiceRequestContext#mutateAdditionalResponseTrailers(Consumer)}
     *                 and {@link AdditionalTrailer} are not included in this trailers.
     */
    @CheckReturnValue
    HttpResponse convertResponse(ServiceRequestContext ctx,
                                 ResponseHeaders headers,
                                 @Nullable Object result,
                                 HttpHeaders trailers) throws Exception;

    /**
     * Throws a {@link FallthroughException} in order to try to convert {@code result} to
     * {@link HttpResponse} by the next converter.
     */
    static <T> T fallthrough() {
        // Always throw the exception quietly.
        throw FallthroughException.get();
    }
}
