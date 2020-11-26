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

package com.linecorp.armeria.server.protobuf;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunctionProvider;
import com.linecorp.armeria.server.protobuf.ProtobufRequestConverterFunction.ResultType;

/**
 * Provides a {@link ProtobufRequestConverterFunction} to annotated services.
 */
@UnstableApi
public final class ProtobufRequestConverterFunctionProvider implements RequestConverterFunctionProvider {

    @Override
    public RequestConverterFunction createRequestConverterFunction(Type requestType,
                                                                   RequestConverterFunction requestConverter) {
        final ResultType resultType = toResultType(requestType);
        if (resultType != ResultType.UNKNOWN) {
            return new ProtobufRequestConverterFunction(resultType);
        } else {
            return null;
        }
    }

    static ResultType toResultType(Type type) {
        if (type instanceof Class) {
            if (isProtobufMessage((Class<?>) type)) {
                return ResultType.PROTOBUF;
            }
        }

        if (type instanceof ParameterizedType) {
            final ParameterizedType parameterizedType = (ParameterizedType) type;
            final Class<?> rawType = (Class<?>) parameterizedType.getRawType();
            if (List.class.isAssignableFrom(rawType)) {
                final Class<?> typeArgument = (Class<?>) parameterizedType.getActualTypeArguments()[0];
                if (isProtobufMessage(typeArgument)) {
                    return ResultType.LIST_PROTOBUF;
                }
            } else if (Set.class.isAssignableFrom(rawType)) {
                final Class<?> typeArgument = (Class<?>) parameterizedType.getActualTypeArguments()[0];
                if (isProtobufMessage(typeArgument)) {
                    return ResultType.SET_PROTOBUF;
                }
            } else if (Map.class.isAssignableFrom(rawType)) {
                final Type[] typeArguments = parameterizedType.getActualTypeArguments();
                final Class<?> keyType = (Class<?>) typeArguments[0];
                if (!String.class.isAssignableFrom(keyType)) {
                    throw new IllegalStateException(
                            keyType + " cannot be used for the key type of Map. " +
                            "(expected: Map<String, ?>)");
                }
                if (isProtobufMessage((Class<?>) typeArguments[1])) {
                    return ResultType.MAP_PROTOBUF;
                }
            }
        }
        return ResultType.UNKNOWN;
    }

    private static boolean isProtobufMessage(Class<?> clazz) {
        return Message.class.isAssignableFrom(clazz);
    }
}
