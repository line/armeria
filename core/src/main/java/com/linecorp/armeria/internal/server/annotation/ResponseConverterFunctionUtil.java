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

package com.linecorp.armeria.internal.server.annotation;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.annotation.ByteArrayResponseConverterFunction;
import com.linecorp.armeria.server.annotation.DelegatingResponseConverterFunctionProvider;
import com.linecorp.armeria.server.annotation.HttpFileResponseConverterFunction;
import com.linecorp.armeria.server.annotation.JacksonResponseConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunctionProvider;
import com.linecorp.armeria.server.annotation.StringResponseConverterFunction;

final class ResponseConverterFunctionUtil {

    private static final Logger logger = LoggerFactory.getLogger(ResponseConverterFunctionUtil.class);

    /**
     * The default {@link ResponseConverterFunction}s.
     */
    private static final List<ResponseConverterFunction> defaultResponseConverters = ImmutableList.of(
            new JacksonResponseConverterFunction(), new StringResponseConverterFunction(),
            new ByteArrayResponseConverterFunction(), new HttpFileResponseConverterFunction());

    private static final List<ResponseConverterFunctionProvider> responseConverterProviders =
            ImmutableList.copyOf(ServiceLoader.load(ResponseConverterFunctionProvider.class,
                                                    ResponseConverterFunctionUtil.class.getClassLoader()));

    private static final List<DelegatingResponseConverterFunctionProvider>
            delegatingResponseConverterProviders = ImmutableList.copyOf(
            ServiceLoader.load(DelegatingResponseConverterFunctionProvider.class,
                               ResponseConverterFunctionUtil.class.getClassLoader()));

    static {
        if (!responseConverterProviders.isEmpty()) {
            logger.debug("Available {}s: {}", ResponseConverterFunctionProvider.class.getSimpleName(),
                         responseConverterProviders);
        }
        if (!delegatingResponseConverterProviders.isEmpty()) {
            logger.debug("Available {}s: {}", DelegatingResponseConverterFunctionProvider.class.getSimpleName(),
                         delegatingResponseConverterProviders);
        }
    }

    static ResponseConverterFunction newResponseConverter(Type returnType,
                                                          List<ResponseConverterFunction> responseConverters) {
        final List<ResponseConverterFunction> nonDelegatingSpiConverters =
                responseConverterProviders.stream()
                                          .map(provider -> provider.createResponseConverterFunction(returnType))
                                          .filter(Objects::nonNull)
                                          .collect(toImmutableList());

        final ImmutableList<ResponseConverterFunction> backingConverters =
                ImmutableList.<ResponseConverterFunction>builder()
                             .addAll(responseConverters)
                             .addAll(nonDelegatingSpiConverters)
                             .addAll(defaultResponseConverters)
                             .build();

        final ResponseConverterFunction responseConverter = new CompositeResponseConverterFunction(
                ImmutableList.<ResponseConverterFunction>builder()
                             .addAll(backingConverters)
                             // It is the last converter to try to convert the result object into an
                             // HttpResponse after aggregating the published object from a Publisher or Stream.
                             .add(new AggregatedResponseConverterFunction(
                                     new CompositeResponseConverterFunction(backingConverters))).build());

        for (final DelegatingResponseConverterFunctionProvider provider
                : delegatingResponseConverterProviders) {
            final ResponseConverterFunction func =
                    provider.createResponseConverterFunction(returnType, responseConverter);
            if (func != null) {
                return func;
            }
        }

        return responseConverter;
    }

    private ResponseConverterFunctionUtil() {}
}
