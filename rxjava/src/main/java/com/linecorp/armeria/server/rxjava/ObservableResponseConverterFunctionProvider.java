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

        if (!ObservableSource.class.isAssignableFrom(toClass(returnType))) {
            return null;
        }

        if (returnType instanceof ParameterizedType) {
            final ParameterizedType p = (ParameterizedType) returnType;
            if (ObservableSource.class.isAssignableFrom(toClass(p.getRawType())) &&
                ObservableSource.class.isAssignableFrom(toClass(p.getActualTypeArguments()[0]))) {
                throw new IllegalStateException(
                        "Cannot support '" + p.getActualTypeArguments()[0].getTypeName() +
                        "' as a generic type of " + ObservableSource.class.getSimpleName());
            }
        }

        return new ObservableResponseConverterFunction(responseConverter, exceptionHandler);
    }

    private static Class<?> toClass(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        }
        return Void.class;
    }
}
