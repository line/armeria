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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.JacksonUtil;

final class ResponseAsUtil {

    static final ObjectMapper OBJECT_MAPPER = JacksonUtil.newDefaultObjectMapper();

    static final Predicate<AggregatedHttpResponse> SUCCESS_PREDICATE = res -> res.status().isSuccess();

    static final ResponseAs<HttpResponse, AggregatedHttpResponse> BLOCKING =
            new ResponseAs<HttpResponse, AggregatedHttpResponse>() {
                @Override
                public AggregatedHttpResponse as(HttpResponse response) {
                    requireNonNull(response, "response");
                    try {
                        return response.aggregate().join();
                    } catch (Exception ex) {
                        return Exceptions.throwUnsafely(Exceptions.peel(ex));
                    }
                }

                @Override
                public boolean requiresAggregation() {
                    return true;
                }
            };

    static <T> FutureResponseAs<T> aggregateAndConvert(ResponseAs<AggregatedHttpResponse, T> responseAs) {
        requireNonNull(responseAs, "responseAs");
        return new FutureResponseAs<T>() {
            @Override
            public CompletableFuture<T> as(HttpResponse response) {
                requireNonNull(response, "response");
                return response.aggregate().thenApply(responseAs::as);
            }

            @Override
            public boolean requiresAggregation() {
                return true;
            }
        };
    }

    private ResponseAsUtil() {}
}
