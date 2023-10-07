/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.testing.junit5.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.reflections.ReflectionUtils;

import com.linecorp.armeria.client.AbstractClientOptionsBuilder;

class TestBlockingWebClientBuilderTest {
    @Test
    void apiConsistency() {
        final Set<Method> overriddenMethods =
                ReflectionUtils.getMethods(TestBlockingWebClientBuilder.class,
                                           method -> Modifier.isPublic(method.getModifiers()) &&
                                                     method.getReturnType()
                                                           .equals(TestBlockingWebClientBuilder.class));
        final Set<Method> superMethods =
                ReflectionUtils.getMethods(AbstractClientOptionsBuilder.class,
                                           method -> Modifier.isPublic(method.getModifiers()));
        for (final Method method : superMethods) {
            final Optional<Method> found = overriddenMethods.stream().filter(method0 -> {
                return method.getName().equals(method0.getName()) &&
                       Arrays.equals(method.getParameterTypes(), method0.getParameterTypes());
            }).findFirst();
            assertTrue(found.isPresent(), method + " has not been overridden");
        }
    }
}
