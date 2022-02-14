/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.internal.common;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.common.stream.AbortedStreamMessage;
import com.linecorp.armeria.server.HttpResponseException;

public final class AbortedHttpResponse extends AbortedStreamMessage<HttpObject> implements HttpResponse {
    public AbortedHttpResponse(Throwable cause) {
        super(cause);
    }

    @Override
    public HttpResponse mapHeaders(Function<? super ResponseHeaders, ? extends ResponseHeaders> function) {
        requireNonNull(function, "function");
        return mapIncludingHttpResponseException(it -> it.mapHeaders(function));
    }

    @Override
    public HttpResponse mapTrailers(
            Function<? super HttpHeaders, ? extends HttpHeaders> function) {
        requireNonNull(function, "function");
        return mapIncludingHttpResponseException(it -> it.mapTrailers(function));
    }

    @Override
    public HttpResponse mapData(
            Function<? super HttpData, ? extends HttpData> function) {
        requireNonNull(function, "function");
        return mapIncludingHttpResponseException(it -> it.mapData(function));
    }

    @Override
    public HttpResponse mapInformational(
            Function<? super ResponseHeaders, ? extends ResponseHeaders> function) {
        requireNonNull(function, "function");
        return mapIncludingHttpResponseException(it -> it.mapInformational(function));
    }

    private HttpResponse mapIncludingHttpResponseException(Function<HttpResponse, HttpResponse> function) {
        requireNonNull(function, "function");
        if (getCause() instanceof HttpResponseException) {
            final HttpResponseException httpResponseException = (HttpResponseException) getCause();
            if (httpResponseException.getCause() == null) {
                return function.apply(httpResponseException.httpResponse());
            } else {
                return HttpResponse.ofFailure(
                        HttpResponseException.of(
                                function.apply(httpResponseException.httpResponse()),
                                httpResponseException.getCause()
                        )
                );
            }
        }
        return HttpResponse.ofFailure(
                HttpResponseException.of(
                        function.apply(HttpResponse.of(500)),
                        getCause()
                )
        );
        // Alternative return this;
    }
}
