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

import static com.linecorp.armeria.internal.server.annotation.ClassUtil.unwrapUnaryAsyncType;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.stream.Stream;

import org.reactivestreams.Publisher;

import com.google.protobuf.Message;
import com.google.protobuf.MessageLite;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunctionProvider;

/**
 * Provides a {@link ProtobufResponseConverterFunction} to annotated services.
 */
@UnstableApi
public final class ProtobufResponseConverterFunctionProvider implements ResponseConverterFunctionProvider {

    @Override
    public ResponseConverterFunction createResponseConverterFunction(Type returnType) {
        if (isSupportedType(returnType)) {
            return new ProtobufResponseConverterFunction();
        }
        return null;
    }

    /**
     * Returns {@code true} if the specified {@link Type} can be handled by the
     * {@link ProtobufResponseConverterFunction}.
     */
    private static boolean isSupportedType(Type type) {
        type = unwrapUnaryAsyncType(type);
        if (type instanceof Class) {
            return MessageLite.class.isAssignableFrom((Class<?>) type);
        }

        if (type instanceof ParameterizedType) {
            final ParameterizedType parameterizedType = (ParameterizedType) type;
            final Class<?> rawType = (Class<?>) parameterizedType.getRawType();

            if (Iterable.class.isAssignableFrom(rawType) || Stream.class.isAssignableFrom(rawType) ||
                Publisher.class.isAssignableFrom(rawType)) {
                final Type actualTypeArgument = parameterizedType.getActualTypeArguments()[0];
                if (!(actualTypeArgument instanceof Class)) {
                    return false;
                }
                return Message.class.isAssignableFrom((Class<?>) actualTypeArgument);
            } else if (Map.class.isAssignableFrom(rawType)) {
                final Type actualTypeArgument = parameterizedType.getActualTypeArguments()[1];
                if (!(actualTypeArgument instanceof Class)) {
                    return false;
                }
                return Message.class.isAssignableFrom((Class<?>) actualTypeArgument);
            }
        }

        return false;
    }
}
