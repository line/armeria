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

package com.linecorp.armeria.client.retry;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.platform.commons.util.ReflectionUtils;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

class RetryRuleTest {

    @Test
    void checkStaticMethods() {
        final Map<Class<?>, Class<? extends AbstractRetryRuleBuilder>> classes =
                ImmutableMap.of(RetryRule.class, RetryRuleBuilder.class,
                                RetryRuleWithContent.class, RetryRuleWithContentBuilder.class);

        classes.forEach((rule, builder) -> {
            final List<Method> builderMethods =
                    ReflectionUtils.findMethods(RetryRuleBuilder.class,
                                                method -> !Modifier.isStatic(method.getModifiers()) &&
                                                          Modifier.isPublic(method.getModifiers()) &&
                                                          method.getName().startsWith("on") &&
                                                          !"onResponseHeaders".equals(method.getName()) &&
                                                          !method.isVarArgs());

            final List<Method> ruleMethods =
                    ReflectionUtils.findMethods(RetryRule.class,
                                                method -> Modifier.isStatic(method.getModifiers()) &&
                                                          Modifier.isPublic(method.getModifiers()) &&
                                                          method.getName().startsWith("on"));
            assertThat(builderMethods).isNotEmpty();
            for (Method builderMethod : builderMethods) {
                final Predicate<Method> predicate = ruleMethod ->
                        ruleMethod.getName().equals(builderMethod.getName()) &&
                        Arrays.equals(ruleMethod.getParameterTypes(), builderMethod.getParameterTypes());

                assertThat(ruleMethods.stream().filter(predicate).collect(toImmutableList())).hasSize(1);
            }
        });
    }

    @Test
    void testOf() {
        final RetryRule retryRule = RetryRule.onException();
        final RetryRule derived = RetryRule.of(retryRule);
        assertThat(derived).isEqualTo(retryRule);

        assertThatThrownBy(() -> RetryRule.of(ImmutableList.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("should not be empty.");
    }
}
