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
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.commons.util.ReflectionUtils;

class AbstractAnnotatedServiceConfigSettersTest {

    @ParameterizedTest
    @ValueSource(classes = {
            ContextPathAnnotatedServiceConfigSetters.class,
            VirtualHostContextPathAnnotatedServiceConfigSetters.class,
            VirtualHostAnnotatedServiceBindingBuilder.class,
            AnnotatedServiceBindingBuilder.class
    })
    void checkReturnTypesOfOverriddenMethods(Class<?> clazz) throws Exception {
        final List<Method> methods = ReflectionUtils.findMethods(AnnotatedServiceConfigSetters.class,
                                                                 method -> true);
        for (Method method: methods) {
            final Method clazzMethod = clazz.getMethod(method.getName(), method.getParameterTypes());
            assertThat(clazzMethod.getReturnType()).isEqualTo(clazz);
        }
    }
}
