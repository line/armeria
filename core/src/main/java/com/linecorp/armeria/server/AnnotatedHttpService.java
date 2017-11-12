/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.google.common.base.MoreObjects;
import com.google.common.base.Throwables;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * {@link PathMapping} and their corresponding {@link BiFunction}.
 */
final class AnnotatedHttpService implements HttpService {

    /**
     * Path param extractor with placeholders, e.g., "/const1/{var1}/{var2}/const2"
     */
    private final HttpHeaderPathMapping pathMapping;

    /**
     * The {@link BiFunction} that will handle the request actually.
     */
    private final BiFunction<ServiceRequestContext, HttpRequest, Object> function;

    /**
     * A decorator of this service.
     */
    private final Function<Service<HttpRequest, HttpResponse>,
            ? extends Service<HttpRequest, HttpResponse>> decorator;

    /**
     * Creates a new instance.
     */
    AnnotatedHttpService(HttpHeaderPathMapping pathMapping,
                         BiFunction<ServiceRequestContext, HttpRequest, Object> function,
                         Function<Service<HttpRequest, HttpResponse>,
                                 ? extends Service<HttpRequest, HttpResponse>> decorator) {
        this.pathMapping = requireNonNull(pathMapping, "pathMapping");
        this.function = requireNonNull(function, "function");
        this.decorator = requireNonNull(decorator, "decorator");
    }

    /**
     * Returns the {@link PathMapping} of this service.
     */
    HttpHeaderPathMapping pathMapping() {
        return pathMapping;
    }

    /**
     * Returns the decorator of this service.
     */
    Function<Service<HttpRequest, HttpResponse>,
            ? extends Service<HttpRequest, HttpResponse>> decorator() {
        return decorator;
    }

    /**
     * Returns whether it's mapping overlaps with given {@link AnnotatedHttpService} instance.
     */
    boolean overlaps(AnnotatedHttpService entry) {
        return false;
        // FIXME(trustin): Make the path overlap detection work again.
        //return !Sets.intersection(methods, entry.methods).isEmpty() &&
        //       pathMapping.skeleton().equals(entry.pathMapping.skeleton());
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {

        final Object ret;
        try {
            ret = function.apply(ctx, req);
        } catch (IllegalArgumentException ignore) {
            throw new HttpResponseException(HttpStatus.BAD_REQUEST);
        }

        if (!(ret instanceof CompletionStage)) {
            return HttpResponse.ofFailure(new IllegalStateException(
                    "illegal return type: " + ret.getClass().getSimpleName()));
        }

        @SuppressWarnings("unchecked")
        CompletionStage<HttpResponse> castStage = (CompletionStage<HttpResponse>) ret;
        return HttpResponse.from(castStage.handle((httpResponse, throwable) -> {
            if (throwable != null) {
                if (Throwables.getRootCause(throwable) instanceof IllegalArgumentException) {
                    return HttpResponse.of(HttpStatus.BAD_REQUEST);
                }
                return Exceptions.throwUnsafely(throwable);
            }
            return httpResponse;
        }));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("pathMapping", pathMapping)
                          .add("function", function).toString();
    }
}
