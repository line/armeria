/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.internal.annotation.AnnotatedHttpService;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

/**
 * FIXME(heowc): update javadoc
 * A configurator to configure {@link ExceptionHandlerFunction}s, {@link RequestConverterFunction}s
 * or {@link ResponseConverterFunction}s for an {@link AnnotatedHttpService}.
 */
final class AnnotatedHttpServiceExtensions {

    /**
     * Creates a new instance with the specified {@code exceptionHandlersAndConverters}.
     */
    static AnnotatedHttpServiceExtensions ofExceptionHandlersAndConverters(
            Iterable<?> exceptionHandlersAndConverters) {

        final Builder<ExceptionHandlerFunction> exceptionHandlers = ImmutableList.builder();
        final Builder<RequestConverterFunction> requestConverters = ImmutableList.builder();
        final Builder<ResponseConverterFunction> responseConverters = ImmutableList.builder();

        for (final Object o : exceptionHandlersAndConverters) {
            if (o instanceof ExceptionHandlerFunction) {
                exceptionHandlers.add((ExceptionHandlerFunction) o);
            } else if (o instanceof RequestConverterFunction) {
                requestConverters.add((RequestConverterFunction) o);
            } else if (o instanceof ResponseConverterFunction) {
                responseConverters.add((ResponseConverterFunction) o);
            } else {
                throw new IllegalArgumentException(o.getClass().getName() +
                                                   " is neither an exception handler nor a converter.");
            }
        }

        return new AnnotatedHttpServiceExtensions(exceptionHandlers.build(), requestConverters.build(),
                                                  responseConverters.build());
    }

    /**
     * The exception handlers of the annotated service.
     */
    private final List<ExceptionHandlerFunction> exceptionHandlers;

    /**
     * The request converters of the annotated service.
     */
    private final List<RequestConverterFunction> requestConverters;

    /**
     * The response converters of the annotated service.
     */
    private final List<ResponseConverterFunction> responseConverters;

    AnnotatedHttpServiceExtensions(
            List<ExceptionHandlerFunction> exceptionHandlers,
            List<RequestConverterFunction> requestConverters,
            List<ResponseConverterFunction> responseConverters) {
        this.exceptionHandlers = requireNonNull(exceptionHandlers, "exceptionHandlers");
        this.requestConverters = requireNonNull(requestConverters, "requestConverters");
        this.responseConverters = requireNonNull(responseConverters, "responseConverters");
    }

    /**
     * Returns the specified {@link ExceptionHandlerFunction}s with the annotated service.
     */
    List<ExceptionHandlerFunction> exceptionHandlers() {
        return exceptionHandlers;
    }

    /**
     * Returns the specified {@link RequestConverterFunction}s with the annotated service.
     */
    List<RequestConverterFunction> requestConverters() {
        return requestConverters;
    }

    /**
     * Returns the specified {@link ResponseConverterFunction}s with the annotated service.
     */
    List<ResponseConverterFunction> responseConverters() {
        return responseConverters;
    }
}
