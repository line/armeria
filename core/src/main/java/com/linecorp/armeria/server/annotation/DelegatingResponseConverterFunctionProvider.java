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
package com.linecorp.armeria.server.annotation;

import java.lang.reflect.Type;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServerBuilder;

/**
 * A {@link ResponseConverterFunction} provider interface which provides a
 * {@link ResponseConverterFunction} that converts an object of the given type to an {@link HttpResponse}
 * using the delegating {@link ResponseConverterFunction}.
 * The delegating converter is a collection of several converters that you specify when
 * {@linkplain ServerBuilder#annotatedService(Object, Object...) creating an annotated service} and
 * Armeria default converters.
 */
@UnstableApi
@FunctionalInterface
public interface DelegatingResponseConverterFunctionProvider {

    /**
     * Returns a {@link ResponseConverterFunction} instance if the function can convert
     * the {@code responseType}, otherwise return {@code null}.
     * The {@link ResponseConverterFunction} passed in is a delegate function which will be used to convert
     * the {@code responseType}.
     *
     * @param responseType the return {@link Type} of the annotated HTTP service method
     * @param responseConverter the delegate {@link ResponseConverterFunction} which converts an object
     *                          into an {@link HttpResponse}
     */
    @Nullable
    ResponseConverterFunction createResponseConverterFunction(Type responseType,
                                                              ResponseConverterFunction responseConverter);
}
