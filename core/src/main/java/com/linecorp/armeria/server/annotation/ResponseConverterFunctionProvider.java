/*
 * Copyright 2018 LINE Corporation
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

/**
 * A {@link ResponseConverterFunction} provider interface which creates a new
 * {@link ResponseConverterFunction} for converting an object of the given type and functions.
 */
@UnstableApi
@FunctionalInterface
public interface ResponseConverterFunctionProvider {

    /**
     * Returns a {@link ResponseConverterFunction} instance if there is a function which can convert
     * the {@code responseType}, otherwise return {@code null}. The {@code responseConverter}
     * and {@code exceptionHandler} are originally configured {@link ResponseConverterFunction}
     * and {@link ExceptionHandlerFunction} which would be used if this provider returns {@code null}.
     *
     * @param responseType the return {@link Type} of the annotated HTTP service method
     * @param responseConverter the {@link ResponseConverterFunction} which converts an object
     *                          into an {@link HttpResponse}
     */
    @Nullable
    ResponseConverterFunction createResponseConverterFunction(Type responseType,
                                                              ResponseConverterFunction responseConverter);
}
