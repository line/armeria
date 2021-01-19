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
package com.linecorp.armeria.server.annotation;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.file.HttpFile;

/**
 * A response converter implementation which creates an {@link HttpResponse} when the {@code result} is
 * an instance of {@link HttpFile}.
 */
public final class HttpFileResponseConverterFunction implements ResponseConverterFunction {

    @Override
    public HttpResponse convertResponse(ServiceRequestContext ctx,
                                        ResponseHeaders headers,
                                        @Nullable Object result,
                                        HttpHeaders trailers) throws Exception {
        if (result instanceof HttpFile) {
            final HttpResponse delegate = ((HttpFile) result).asService().serve(ctx, ctx.request());
            return new FilteredHttpResponse(delegate) {
                private boolean trailerSent;

                @Override
                protected HttpObject filter(HttpObject obj) {
                    if (obj instanceof ResponseHeaders) {
                        return ((ResponseHeaders) obj).toBuilder().set(headers).build();
                    }
                    if (obj instanceof HttpHeaders) {
                        trailerSent = true;
                        return !trailers.isEmpty() ? ((HttpHeaders) obj).toBuilder().set(trailers).build()
                                                   : obj;
                    }

                    return obj;
                }

                @Override
                protected void beforeComplete(Subscriber<? super HttpObject> subscriber) {
                    if (!trailers.isEmpty() && !trailerSent) {
                        subscriber.onNext(trailers);
                    }
                }
            };
        }

        return ResponseConverterFunction.fallthrough();
    }
}
