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

import java.lang.reflect.Type;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.FallthroughException;
import com.linecorp.armeria.server.annotation.HttpResult;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

/**
 * A {@link ResponseConverterFunction} which wraps a list of {@link ResponseConverterFunction}s.
 */
final class CompositeResponseConverterFunction implements ResponseConverterFunction {

    private final ImmutableList<ResponseConverterFunction> functions;

    CompositeResponseConverterFunction(ImmutableList<ResponseConverterFunction> functions) {
        this.functions = functions;
    }

    @Override
    @Nullable
    public Boolean isResponseStreaming(Type returnType, @Nullable MediaType produceType) {
        for (ResponseConverterFunction function : functions) {
            final Boolean responseStreaming = function.isResponseStreaming(returnType, produceType);
            if (responseStreaming != null) {
                return responseStreaming;
            }
        }
        return null;
    }

    @Override
    public HttpResponse convertResponse(ServiceRequestContext ctx,
                                        ResponseHeaders headers,
                                        @Nullable Object result,
                                        HttpHeaders trailers) throws Exception {
        if (result instanceof HttpResponse) {
            return (HttpResponse) result;
        }

        if (result instanceof ResponseEntity) {
            final ResponseEntity<?> responseEntity = (ResponseEntity<?>) result;
            headers = ResponseEntityUtil.buildResponseHeaders(ctx, responseEntity);
            result = responseEntity.hasContent() ? responseEntity.content() : null;
            trailers = responseEntity.trailers();
        } else if (result instanceof HttpResult) {
            final HttpResult<?> httpResult = (HttpResult<?>) result;
            headers = HttpResultUtil.buildResponseHeaders(ctx, httpResult);
            result = httpResult.content();
            trailers = httpResult.trailers();
        }
        try (SafeCloseable ignored = ctx.push()) {
            for (final ResponseConverterFunction func : functions) {
                try {
                    return func.convertResponse(ctx, headers, result, trailers);
                } catch (FallthroughException ignore) {
                    // Do nothing.
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "Response converter " + func.getClass().getName() +
                            " cannot convert a result to HttpResponse: " + result, e);
                }
            }
        }
        // There is no response converter which is able to convert 'null' result to a response.
        // In this case, a response with the specified HTTP headers would be sent.
        // If you want to force to send '204 No Content' for this case, add
        // 'NullToNoContentResponseConverterFunction' to the list of response converters.
        if (result == null) {
            return HttpResponse.of(headers, HttpData.empty(), trailers);
        }
        throw new IllegalStateException(
                "No response converter exists for a result: " + result.getClass().getName());
    }
}
