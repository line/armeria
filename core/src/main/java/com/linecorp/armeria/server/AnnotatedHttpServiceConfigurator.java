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
 * A configurator to configure {@link ExceptionHandlerFunction}s, {@link RequestConverterFunction}s
 * or {@link ResponseConverterFunction}s for an {@link AnnotatedHttpService}.
 */
final class AnnotatedHttpServiceConfigurator {

    /**
     * Creates a new instance with the specified {@code exceptionHandlersAndConverters}.
     */
    static AnnotatedHttpServiceConfigurator ofExceptionHandlersAndConverters(
            Iterable<?> exceptionHandlersAndConverters) {

        Builder<ExceptionHandlerFunction> exceptionHandlers = null;
        Builder<RequestConverterFunction> requestConverters = null;
        Builder<ResponseConverterFunction> responseConverters = null;

        for (final Object o : exceptionHandlersAndConverters) {
            boolean added = false;
            if (o instanceof ExceptionHandlerFunction) {
                if (exceptionHandlers == null) {
                    exceptionHandlers = ImmutableList.builder();
                }
                exceptionHandlers.add((ExceptionHandlerFunction) o);
                added = true;
            }
            if (o instanceof RequestConverterFunction) {
                if (requestConverters == null) {
                    requestConverters = ImmutableList.builder();
                }
                requestConverters.add((RequestConverterFunction) o);
                added = true;
            }
            if (o instanceof ResponseConverterFunction) {
                if (responseConverters == null) {
                    responseConverters = ImmutableList.builder();
                }
                responseConverters.add((ResponseConverterFunction) o);
                added = true;
            }
            if (!added) {
                throw new IllegalArgumentException(o.getClass().getName() +
                                                   " is neither an exception handler nor a converter.");
            }
        }

        final List<ExceptionHandlerFunction> exceptionHandlerFunctions =
                exceptionHandlers != null ? exceptionHandlers.build() : ImmutableList.of();
        final List<RequestConverterFunction> requestConverterFunctions =
                requestConverters != null ? requestConverters.build() : ImmutableList.of();
        final List<ResponseConverterFunction> responseConverterFunctions =
                responseConverters != null ? responseConverters.build() : ImmutableList.of();

        return ofExceptionHandlersAndConverters(exceptionHandlerFunctions, requestConverterFunctions,
                                                responseConverterFunctions);
    }

    /**
     * Creates a new instance with the specified {@link ExceptionHandlerFunction}s,
     * {@link RequestConverterFunction}s and {@link ResponseConverterFunction}s.
     */
    static AnnotatedHttpServiceConfigurator ofExceptionHandlersAndConverters(
            List<ExceptionHandlerFunction> exceptionHandlerFunctions,
            List<RequestConverterFunction> requestConverterFunctions,
            List<ResponseConverterFunction> responseConverterFunctions) {

        final AnnotatedHttpServiceConfigurator configurator = new AnnotatedHttpServiceConfigurator();
        configurator.configureExceptionHandlers(exceptionHandlerFunctions);
        configurator.configureRequestConverters(requestConverterFunctions);
        configurator.configureResponseConverters(responseConverterFunctions);
        return configurator;
    }

    /**
     * The exception handlers of the annotated service.
     */
    private List<ExceptionHandlerFunction> exceptionHandlers = ImmutableList.of();

    /**
     * The request converters of the annotated service.
     */
    private List<RequestConverterFunction> requestConverters = ImmutableList.of();

    /**
     * The response converters of the annotated service.
     */
    private List<ResponseConverterFunction> responseConverters = ImmutableList.of();

    /**
     * Configures the specified {@link ExceptionHandlerFunction}s with the annotated service.
     */
    void configureExceptionHandlers(List<ExceptionHandlerFunction> exceptionHandlers) {
        requireNonNull(exceptionHandlers, "exceptionHandlers");
        this.exceptionHandlers = ImmutableList.<ExceptionHandlerFunction>builder()
                .addAll(this.exceptionHandlers)
                .addAll(ImmutableList.copyOf(exceptionHandlers))
                .build();
    }

    /**
     * Returns the specified {@link ExceptionHandlerFunction}s with the annotated service.
     */
    List<ExceptionHandlerFunction> exceptionHandlers() {
        return exceptionHandlers;
    }

    /**
     * Configures the specified {@link RequestConverterFunction}s with the annotated service.
     */
    void configureRequestConverters(List<RequestConverterFunction> requestConverters) {
        requireNonNull(requestConverters, "requestConverters");
        this.requestConverters = ImmutableList.<RequestConverterFunction>builder()
                .addAll(this.requestConverters)
                .addAll(ImmutableList.copyOf(requestConverters))
                .build();
    }

    /**
     * Returns the specified {@link RequestConverterFunction}s with the annotated service.
     */
    List<RequestConverterFunction> requestConverters() {
        return requestConverters;
    }

    /**
     * Configures the specified {@link ResponseConverterFunction}s with the annotated service.
     */
    void configureResponseConverters(List<ResponseConverterFunction> responseConverters) {
        requireNonNull(responseConverters, "responseConverters");
        this.responseConverters = ImmutableList.<ResponseConverterFunction>builder()
                .addAll(this.responseConverters)
                .addAll(ImmutableList.copyOf(responseConverters))
                .build();
    }

    /**
     * Returns the specified {@link ResponseConverterFunction}s with the annotated service.
     */
    List<ResponseConverterFunction> responseConverters() {
        return responseConverters;
    }
}
