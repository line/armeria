/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.hessian;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.util.concurrent.ListenableFuture;

import com.linecorp.armeria.internal.common.hessian.HessianMethod;
import com.linecorp.armeria.internal.common.hessian.HessianMethod.ResponseType;

/**
 * test function parser.
 *
 * @author eisig
 */
class HessianMethodTest {

    private static final Map<String, Method> methodMap = new HashMap<>();

    @BeforeAll
    static void setup() {
        final Method[] methods = DemoService.class.getMethods();
        for (Method method : methods) {
            methodMap.put(method.getName(), method);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "fun1", "fun2" })
    void testParseSync(String name) {
        final HessianMethod fun1 = HessianMethod.of(DemoService.class, methodMap.get(name), name, null);
        assertThat(fun1).hasFieldOrPropertyWithValue("responseType", ResponseType.OTHER_OBJECTS);
        if ("fun1".equals(name)) {
            assertThat(fun1).hasFieldOrPropertyWithValue("returnValueType", String.class);
        } else {
            assertThat(fun1).hasFieldOrPropertyWithValue("returnValueType", Object.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "listenableFun" })
    void testListenableFuture(String name) {
        final HessianMethod fun1 = HessianMethod.of(DemoService.class, methodMap.get(name), name, null);
        assertThat(fun1).hasFieldOrPropertyWithValue("responseType", ResponseType.OTHER_OBJECTS)
                        .hasFieldOrPropertyWithValue("returnValueType", ListenableFuture.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "asyncFun2", "asyncFun3" })
    void testAsync(String name) {
        final HessianMethod fun1 = HessianMethod.of(DemoService.class, methodMap.get(name), name, null);
        assertThat(fun1).hasFieldOrPropertyWithValue("responseType", ResponseType.COMPLETION_STAGE)
                        .hasFieldOrPropertyWithValue("returnValueType", DemoResponse.class);
    }

    @Test
    void testNested() {
        final String name = "nested";
        final HessianMethod fun1 = HessianMethod.of(DemoService.class, methodMap.get(name), name, null);
        assertThat(fun1).hasFieldOrPropertyWithValue("responseType", ResponseType.COMPLETION_STAGE)
                        .hasFieldOrPropertyWithValue("returnValueType", List.class);
    }

    @Test
    void testNested2() {
        final String name = "nested2";
        final HessianMethod fun1 = HessianMethod.of(DemoService.class, methodMap.get(name), name, null);
        assertThat(fun1).hasFieldOrPropertyWithValue("responseType", ResponseType.OTHER_OBJECTS)
                        .hasFieldOrPropertyWithValue("returnValueType", List.class);
    }

    interface DemoService {

        String fun1();

        Object fun2();

        // not support as async yet.
        ListenableFuture<String> listenableFun();

        // not support as async yet.
        Future<DemoResponse> futureFun1();

        CompletableFuture<DemoResponse> asyncFun2();

        CompletionStage<DemoResponse> asyncFun3();

        CompletableFuture<List<String>> nested();

        List<List<String>> nested2();
    }

    static class DemoResponse {

        private String msg;
    }
}
