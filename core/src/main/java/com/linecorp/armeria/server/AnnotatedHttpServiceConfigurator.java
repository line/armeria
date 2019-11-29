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

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.internal.annotation.AnnotatedHttpService;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

/**
 * A configurator to configure {@link ExceptionHandlerFunction}s, {@link RequestConverterFunction}s
 * or {@link ResponseConverterFunction}s for an {@link AnnotatedHttpService}.
 */
final class AnnotatedHttpServiceConfigurator {

    /**
     * Creates a new instance with the specified {@code exceptionHandlersAndConverters}.
     */
    static AnnotatedHttpServiceConfigurator ofExceptionHandlersAndConverters(
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

        final AnnotatedHttpServiceConfigurator configurator = new AnnotatedHttpServiceConfigurator();
        configurator.exceptionHandlers.addAll(exceptionHandlers.build());
        configurator.requestConverters.addAll(requestConverters.build());
        configurator.responseConverters.addAll(responseConverters.build());
        return configurator;
    }

    /**
     * The exception handlers of the annotated service.
     */
    private final Builder<ExceptionHandlerFunction> exceptionHandlers = ImmutableList.builder();

    /**
     * The request converters of the annotated service.
     */
    private final Builder<RequestConverterFunction> requestConverters = ImmutableList.builder();

    /**
     * The response converters of the annotated service.
     */
    private final Builder<ResponseConverterFunction> responseConverters = ImmutableList.builder();

    /**
     * Returns the specified {@link ExceptionHandlerFunction}s with the annotated service.
     */
    List<ExceptionHandlerFunction> exceptionHandlers() {
        return exceptionHandlers.build();
    }

    /**
     * Returns the specified {@link RequestConverterFunction}s with the annotated service.
     */
    List<RequestConverterFunction> requestConverters() {
        return requestConverters.build();
    }

    /**
     * Returns the specified {@link ResponseConverterFunction}s with the annotated service.
     */
    List<ResponseConverterFunction> responseConverters() {
        return responseConverters.build();
    }
}
