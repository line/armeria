/*
 * Copyright 2020 LINE Corporation
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

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A {@link RequestConverterFunction} provider interface which creates a new
 * {@link RequestConverterFunction} for converting an {@link AggregatedHttpRequest} to an object of
 * the given type.
 */
@UnstableApi
@FunctionalInterface
public interface RequestConverterFunctionProvider {

    /**
     * Returns a {@link RequestConverterFunction} instance if there is a function which can convert
     * the {@code requestType}, otherwise return {@code null}. The {@code requestConverter} is originally
     * configured {@link ResponseConverterFunction} which would be used if this provider returns {@code null}.
     *
     * @param requestType the input {@link Type} of the annotated HTTP service method
     * @param requestConverter the {@link RequestConverterFunction} which converts
     *                         an {@link AggregatedHttpRequest} into an object
     */
    @Nullable
    RequestConverterFunction createRequestConverterFunction(Type requestType,
                                                            RequestConverterFunction requestConverter);
}
