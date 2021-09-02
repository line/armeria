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
package com.linecorp.armeria.internal.server.rxjava3;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunctionProvider;
import com.linecorp.armeria.server.rxjava3.ObservableResponseConverterFunction;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;

/**
 * Provides an {@link ObservableResponseConverterFunction} to annotated services.
 */
@UnstableApi
public final class ObservableResponseConverterFunctionProvider implements ResponseConverterFunctionProvider {

    @Nullable
    @Override
    public ResponseConverterFunction createResponseConverterFunction(
            Type returnType,
            ResponseConverterFunction responseConverter) {
        @Nullable
        final Class<?> clazz = typeToClass(returnType);
        if (clazz != null && isSupportedClass(clazz)) {
            ensureNoMoreObservableSource(returnType, returnType);
            return new ObservableResponseConverterFunction(responseConverter);
        }
        return null;
    }

    private static void ensureNoMoreObservableSource(Type returnType, Type type) {
        if (type instanceof ParameterizedType) {
            final Type[] args = ((ParameterizedType) type).getActualTypeArguments();
            for (Type arg : args) {
                final Class<?> clazz = typeToClass(arg);
                if (clazz != null && isSupportedClass(clazz)) {
                    throw new IllegalStateException(
                            "Disallowed type exists in the generic type arguments of the return type '" +
                            returnType + "': " + clazz.getName());
                }
                ensureNoMoreObservableSource(returnType, arg);
            }
        }
    }

    /**
     * Returns {@code true} if the specified {@link Class} can be handled by the
     * {@link ObservableResponseConverterFunction}.
     */
    private static boolean isSupportedClass(Class<?> clazz) {
        return Observable.class.isAssignableFrom(clazz) ||
               Maybe.class.isAssignableFrom(clazz) ||
               Single.class.isAssignableFrom(clazz) ||
               Completable.class.isAssignableFrom(clazz);
    }

    /**
     * Converts the specified {@link Type} to a {@link Class} instance.
     */
    @Nullable
    private static Class<?> typeToClass(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        }
        return null;
    }

    @Override
    public String toString() {
        return ObservableResponseConverterFunctionProvider.class.getSimpleName();
    }
}
