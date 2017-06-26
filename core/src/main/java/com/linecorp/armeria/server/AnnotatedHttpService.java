/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;

/**
 * {@link HttpMethod}, {@link PathMapping} and their corresponding {@link BiFunction}.
 */
final class AnnotatedHttpService implements HttpService {
    // TODO(trustin): Allow binding multiple functions.
    //                https://github.com/line/armeria/issues/579

    /**
     * EnumSet of HTTP Method, e.g., GET, POST, PUT, ...
     */
    private final Set<HttpMethod> methods;

    /**
     * Path param extractor with placeholders, e.g., "/const1/{var1}/{var2}/const2"
     */
    private final PathMapping pathMapping;

    /**
     * The {@link BiFunction} that will handle the request actually.
     */
    private final BiFunction<ServiceRequestContext, HttpRequest, Object> function;

    /**
     * Creates a new instance.
     */
    AnnotatedHttpService(Iterable<HttpMethod> methods, PathMapping pathMapping,
                         BiFunction<ServiceRequestContext, HttpRequest, Object> function) {
        this.methods = Sets.immutableEnumSet(requireNonNull(methods, "methods"));
        this.pathMapping = requireNonNull(pathMapping, "pathMapping");
        this.function = requireNonNull(function, "function");
    }

    /**
     * Returns the {@link PathMapping} of this service.
     */
    PathMapping pathMapping() {
        return pathMapping;
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
        if (!methods.contains(ctx.method())) {
            return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
        }

        final Object ret = function.apply(ctx, req);
        if (!(ret instanceof CompletionStage)) {
            return HttpResponse.ofFailure(new IllegalStateException(
                    "illegal return type: " + ret.getClass().getSimpleName()));
        }

        @SuppressWarnings("unchecked")
        CompletionStage<HttpResponse> castStage = (CompletionStage<HttpResponse>) ret;
        return HttpResponse.from(castStage);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("methods", methods)
                          .add("pathMapping", pathMapping)
                          .add("function", function).toString();
    }
}
