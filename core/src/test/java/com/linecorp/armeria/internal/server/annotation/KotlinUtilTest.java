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

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

/**
 * Without kotlin dependencies, all functions return false or null safely.
 */
class KotlinUtilTest {

    @Test
    void getCallKotlinSuspendingMethod() {
        assertThat(KotlinUtil.getCallKotlinSuspendingMethod()).isNull();
    }

    @Test
    void isContinuation() {
        assertThat(KotlinUtil.isContinuation(String.class)).isFalse();
    }

    @Test
    void isKotlinMethod() throws NoSuchMethodException {
        final Method testMethod = DummyService.class.getDeclaredMethod("testMethod");
        assertThat(KotlinUtil.isKotlinMethod(testMethod)).isFalse();
    }

    @Test
    void isSuspendingFunction() throws NoSuchMethodException {
        final Method testMethod = DummyService.class.getDeclaredMethod("testMethod");
        assertThat(KotlinUtil.isSuspendingFunction(testMethod)).isFalse();
    }

    @Test
    void isSuspendingAndReturnTypeUnit() throws NoSuchMethodException {
        final Method testMethod = DummyService.class.getDeclaredMethod("testMethod");
        assertThat(KotlinUtil.isSuspendingAndReturnTypeUnit(testMethod)).isFalse();
    }

    private static class DummyService {
        void testMethod() {
        }
    }
}
