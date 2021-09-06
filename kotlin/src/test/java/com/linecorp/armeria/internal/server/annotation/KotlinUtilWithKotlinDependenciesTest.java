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

package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.kotlin.ExampleService;
import com.linecorp.armeria.server.ServiceRequestContext;

import kotlin.coroutines.Continuation;

class KotlinUtilWithKotlinDependenciesTest {
    final ExampleService exampleService;
    final Method normal;
    final Method suspendingUnit;
    final Method suspendingInt;

    KotlinUtilWithKotlinDependenciesTest() throws NoSuchMethodException {
        exampleService = new ExampleService();
        normal = ExampleService.class.getDeclaredMethod("normal");
        suspendingUnit = ExampleService.class.getDeclaredMethod("suspendingUnit", Continuation.class);
        suspendingInt = ExampleService.class.getDeclaredMethod("suspendingInt", Continuation.class);
    }

    @Test
    void getCallKotlinSuspendingMethod() {
        assertThat(KotlinUtil.getCallKotlinSuspendingMethod()).isNotNull();
    }

    @Test
    void invokeSuspendingInt() throws Throwable {
        final MethodHandle callSuspendingMethod = KotlinUtil.getCallKotlinSuspendingMethod();

        final RequestContext ctx = getRequestContext();
        final CompletableFuture<?> res =
                (CompletableFuture<?>) callSuspendingMethod.invoke(suspendingInt, exampleService,
                                                                   new Object[0], ctx.eventLoop(), ctx);
        assertThat(res.get()).isEqualTo(1);
    }

    @Test
    void invokeSuspendingUnit() throws Throwable {
        final MethodHandle callSuspendingMethod = KotlinUtil.getCallKotlinSuspendingMethod();

        final RequestContext ctx = getRequestContext();
        final CompletableFuture<?> res =
                (CompletableFuture<?>) callSuspendingMethod.invoke(suspendingUnit, exampleService,
                                                                   new Object[0], ctx.eventLoop(), ctx);
        assertThat(res.get()).isNull();
    }

    @Test
    void isContinuation() {
        assertThat(KotlinUtil.isContinuation(String.class)).isFalse();
        assertThat(KotlinUtil.isContinuation(Continuation.class)).isTrue();
    }

    @Test
    void isKotlinMethod() {
        assertThat(KotlinUtil.isKotlinMethod(normal)).isTrue();
        assertThat(KotlinUtil.isKotlinMethod(suspendingInt)).isTrue();
        assertThat(KotlinUtil.isKotlinMethod(suspendingUnit)).isTrue();
    }

    @Test
    void isSuspendingFunction() {
        assertThat(KotlinUtil.isSuspendingFunction(normal)).isFalse();
        assertThat(KotlinUtil.isSuspendingFunction(suspendingInt)).isTrue();
        assertThat(KotlinUtil.isSuspendingFunction(suspendingUnit)).isTrue();
    }

    @Test
    void isSuspendingAndReturnTypeUnit() {
        assertThat(KotlinUtil.isSuspendingAndReturnTypeUnit(normal)).isFalse();
        assertThat(KotlinUtil.isSuspendingAndReturnTypeUnit(suspendingInt)).isFalse();
        assertThat(KotlinUtil.isSuspendingAndReturnTypeUnit(suspendingUnit)).isTrue();
    }

    private static RequestContext getRequestContext() {
        return ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/test"));
    }
}
