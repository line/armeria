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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reflections.ReflectionUtils;

class AbstractServiceBindingBuilderTest {

    @ParameterizedTest
    @ValueSource(classes = {
            ContextPathServiceBindingBuilder.class,
            VirtualHostContextPathServiceBindingBuilder.class,
            ServiceBindingBuilder.class,
            VirtualHostServiceBindingBuilder.class,
    })
    void apiConsistency(Class<?> clazz) {
        final Set<Method> overriddenMethods =
                ReflectionUtils.getMethods(clazz,
                                           method -> Modifier.isPublic(method.getModifiers()) &&
                                                     method.getReturnType().equals(clazz));
        final Set<Method> superMethods =
                ReflectionUtils.getMethods(AbstractServiceBindingBuilder.class,
                                           method -> Modifier.isPublic(method.getModifiers()));
        for (final Method method : superMethods) {
            assertThat(overriddenMethods)
                    .as("%s is not overridden by %s", method, clazz)
                    .filteredOn(tMethod -> {
                        return method.getName().equals(tMethod.getName()) &&
                               Arrays.equals(method.getParameterTypes(), tMethod.getParameterTypes());
                    }).hasSize(1);
        }
    }
}
