/*
 *  Copyright 2022 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import reactor.core.publisher.Mono;

class ClassUtilTest {

    @Test
    void shouldCastTypeToClass() throws NoSuchMethodException {
        Method method = ClassUtilTest.class.getDeclaredMethod("classReturnType");
        Type returnType = method.getGenericReturnType();
        Class<?> clazz = ClassUtil.typeToClass(returnType);
        assertThat(clazz).isAssignableFrom(String.class);

        method = ClassUtilTest.class.getDeclaredMethod("genericReturnType");
        returnType = method.getGenericReturnType();
        clazz = ClassUtil.typeToClass(returnType);
        assertThat(clazz).isAssignableFrom(List.class);
    }

    @CsvSource({"future", "mono"})
    @ParameterizedTest
    void shouldUnwrapAsyncType(String methodName) throws NoSuchMethodException {
        final Method method = ClassUtilTest.class.getDeclaredMethod(methodName);
        final Type returnType = method.getGenericReturnType();
        final Type type = ClassUtil.unwrapUnaryAsyncType(returnType);
        assertThat((Class<?>) type).isAssignableFrom(String.class);
    }

    private static List<String> genericReturnType() {
        return null;
    }

    private static String classReturnType() {
        return null;
    }

    private static CompletableFuture<String> future() {
        return null;
    }

    private static Mono<String> mono() {
        return null;
    }
}
