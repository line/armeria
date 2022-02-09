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

import java.util.function.Function;

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
        if (getCause() instanceof HttpResponseException) {
            final HttpResponseException httpResponseException = (HttpResponseException) getCause();
            return new AbortedHttpResponse(
                    HttpResponseException.of(
                            httpResponseException.httpResponse().mapHeaders(function),
                            httpResponseException.getCause()
                    )
            );
        }
        return HttpResponse.super.mapHeaders(function);
    }
}
