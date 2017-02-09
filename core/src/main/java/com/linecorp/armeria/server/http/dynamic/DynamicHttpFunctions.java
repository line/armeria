/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http.dynamic;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.internal.Types;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Provides various utility functions for internal use related with {@link DynamicHttpFunction}.
 */
final class DynamicHttpFunctions {

    /**
     * Returns {@link ResponseConverter} instance which can convert the given {@code type} object into
     * {@link HttpResponse}, from the configured converters.
     *
     * @throws IllegalArgumentException if no appropriate {@link ResponseConverter} exists for given
     * {@code type}
     */
    private static ResponseConverter converter(Class<?> type, Map<Class<?>, ResponseConverter> converters) {
        // Search for the converter mapped to itself or one of its superclasses, except Object.class.
        Class<?> current = type;
        while (current != Object.class) {
            ResponseConverter converter = converters.get(current);
            if (converter != null) {
                return converter;
            }
            current = current.getSuperclass();
        }

        // Search for the converter mapped to one of its interface.
        for (Class<?> itfc : Types.getAllInterfaces(type)) {
            ResponseConverter converter = converters.get(itfc);
            if (converter != null) {
                return converter;
            }
        }

        // Search for the converter mapped to Object.class.
        if (converters.containsKey(Object.class)) {
            return converters.get(Object.class);
        }

        // No appropriate converter found: raise runtime exception.
        throw new IllegalArgumentException("Converter not available for " + type.getSimpleName());
    }

    /**
     * Converts {@code object} into {@link HttpResponse}, using one of the given {@code converters}.
     *
     * @throws IllegalStateException if an {@link Exception} thrown during conversion
     */
    private static HttpResponse convert(Object object, Map<Class<?>, ResponseConverter> converters) {
        if (object instanceof HttpResponse) {
            return (HttpResponse) object;
        } else {
            ResponseConverter converter = converter(object.getClass(), converters);
            try {
                return converter.convert(object);
            } catch (Exception e) {
                throw new IllegalStateException("Exception occurred during ResponseConverter#convert", e);
            }
        }
    }

    /**
     * Converts {@code object} into {@link HttpResponse}, using the given {@code converter}.
     *
     * @throws IllegalStateException if an {@link Exception} thrown during conversion
     */
    private static HttpResponse convert(Object object, ResponseConverter converter) {
        if (object instanceof HttpResponse) {
            return (HttpResponse) object;
        } else {
            try {
                return converter.convert(object);
            } catch (Exception e) {
                throw new IllegalStateException("Exception occurred during ResponseConverter#convert", e);
            }
        }
    }

    /**
     * Creates a new {@link DynamicHttpFunction} object by combining given {@link DynamicHttpFunction} and
     * {@link ResponseConverter} object.
     */
    static DynamicHttpFunction of(DynamicHttpFunction function, ResponseConverter converter) {
        return (ctx, req, args) ->
                executeSyncOrAsync(function, ctx, req, args).thenApply(obj -> convert(obj, converter));
    }

    /**
     * Creates a new {@link DynamicHttpFunction} object by combining the given {@link DynamicHttpFunction} and
     * {@link ResponseConverter} objects.
     */
    static DynamicHttpFunction of(DynamicHttpFunction function,
                                  Map<Class<?>, ResponseConverter> converters) {
        return (ctx, req, args) ->
                executeSyncOrAsync(function, ctx, req, args).thenApply(obj -> convert(obj, converters));
    }

    private static CompletionStage<?> executeSyncOrAsync(
            DynamicHttpFunction function,
            ServiceRequestContext ctx,
            HttpRequest req,
            Map<String, String> args) throws Exception {
        Object ret = function.serve(ctx, req, args);
        return ret instanceof CompletionStage ?
               (CompletionStage<?>) ret : CompletableFuture.completedFuture(ret);
    }

    private DynamicHttpFunctions() {}
}
