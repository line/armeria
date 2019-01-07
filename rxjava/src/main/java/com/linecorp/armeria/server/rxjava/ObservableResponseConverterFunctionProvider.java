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
package com.linecorp.armeria.server.rxjava;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import javax.annotation.Nullable;

import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunctionProvider;

import io.reactivex.ObservableSource;

public class ObservableResponseConverterFunctionProvider implements ResponseConverterFunctionProvider {

    @Nullable
    @Override
    public ResponseConverterFunction createResponseConverterFunction(
            Type returnType,
            ResponseConverterFunction responseConverter,
            ExceptionHandlerFunction exceptionHandler) {
        final Class<?> clazz = typeToClass(returnType);
        if (clazz != null && ObservableSource.class.isAssignableFrom(clazz)) {
            assert returnType instanceof ParameterizedType;
            ensureNoMoreObservableSource(returnType, returnType);
            return new ObservableResponseConverterFunction(responseConverter, exceptionHandler);
        }
        return null;
    }

    private static void ensureNoMoreObservableSource(Type returnType, Type type) {
        if (type instanceof ParameterizedType) {
            final Type[] args = ((ParameterizedType) type).getActualTypeArguments();
            for (Type arg : args) {
                final Class<?> clazz = typeToClass(arg);
                if (clazz != null && ObservableSource.class.isAssignableFrom(clazz)) {
                    throw new IllegalStateException(
                            "Disallowed type exists in the generic type arguments of the return type '" +
                            returnType + "': " + clazz.getName());
                }
                ensureNoMoreObservableSource(returnType, arg);
            }
        }
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
}
