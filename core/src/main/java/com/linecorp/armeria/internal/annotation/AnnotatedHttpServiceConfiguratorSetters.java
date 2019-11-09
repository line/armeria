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

package com.linecorp.armeria.internal.annotation;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

public final class AnnotatedHttpServiceConfiguratorSetters {

    private final Builder<ExceptionHandlerFunction> exceptionHandlers = ImmutableList.builder();
    private final Builder<RequestConverterFunction> requestConverters = ImmutableList.builder();
    private final Builder<ResponseConverterFunction> responseConverters = ImmutableList.builder();

    /**
     * Configures the specified {@link ExceptionHandlerFunction}s with the annotated service.
     */
    public void configureExceptionHandlers(Iterable<ExceptionHandlerFunction> exceptionHandlers) {
        requireNonNull(exceptionHandlers, "exceptionHandlers");
        this.exceptionHandlers.addAll(exceptionHandlers);
    }

    /**
     * Configures a specified {@link ExceptionHandlerFunction} with the annotated service.
     */
    public void configureExceptionHandlers(ExceptionHandlerFunction exceptionHandler) {
        requireNonNull(exceptionHandler, "exceptionHandler");
        exceptionHandlers.add(exceptionHandler);
    }

    /**
     * Configures the specified {@link RequestConverterFunction}s with the annotated service.
     */
    public void configureRequestConverters(Iterable<RequestConverterFunction> requestConverters) {
        requireNonNull(requestConverters, "requestConverters");
        this.requestConverters.addAll(requestConverters);
    }

    /**
     * Configures a specified {@link RequestConverterFunction} with the annotated service.
     */
    public void configureRequestConverters(RequestConverterFunction requestConverter) {
        requireNonNull(requestConverter, "requestConverter");
        requestConverters.add(requestConverter);
    }

    /**
     * Configures the specified {@link ResponseConverterFunction}s with the annotated service.
     */
    public void configureResponseConverters(Iterable<ResponseConverterFunction> responseConverters) {
        requireNonNull(responseConverters, "responseConverters");
        this.responseConverters.addAll(responseConverters);
    }

    /**
     * Configures a specified {@link ResponseConverterFunction} with the annotated service.
     */
    public void configureResponseConverters(ResponseConverterFunction responseConverter) {
        requireNonNull(responseConverter, "responseConverter");
        responseConverters.add(responseConverter);
    }

    /**
     * Creates a new instance with the specified {@code exceptionHandlersAndConverters}.
     */
    static AnnotatedHttpServiceConfiguratorSetters ofExceptionHandlersAndConverters(
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
     * {@link RequestConverterFunction} and {@link ResponseConverterFunction}.
     */
    static AnnotatedHttpServiceConfiguratorSetters ofExceptionHandlersAndConverters(
            List<ExceptionHandlerFunction> exceptionHandlerFunctions,
            List<RequestConverterFunction> requestConverterFunctions,
            List<ResponseConverterFunction> responseConverterFunctions) {

        final AnnotatedHttpServiceConfiguratorSetters setters = new AnnotatedHttpServiceConfiguratorSetters();

        setters.configureExceptionHandlers(exceptionHandlerFunctions);
        setters.configureRequestConverters(requestConverterFunctions);
        setters.configureResponseConverters(responseConverterFunctions);
        return setters;
    }

    /**
     * Converts this setter to a {@link AnnotatedHttpServiceConfigurator} in order to fill unspecified values.
     */
    AnnotatedHttpServiceConfigurator toAnnotatedServiceConfigurator() {
        final AnnotatedHttpServiceConfigurator configurator = new AnnotatedHttpServiceConfigurator();
        configurator.configureExceptionHandlers(exceptionHandlers.build());
        configurator.configureRequestConverters(requestConverters.build());
        configurator.configureResponseConverters(responseConverters.build());
        return configurator;
    }
}
