/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;

class BlockingWebClientTest {

    @Test
    void apiConsistencyWithWebClient() {
        final List<Method> webClientMethods =
                Arrays.stream(WebClient.class.getDeclaredMethods())
                      .filter(method -> method.getReturnType().isAssignableFrom(HttpResponse.class))
                      .collect(toImmutableList());

        final List<Method> blockingClientMethods =
                Arrays.stream(BlockingWebClient.class.getDeclaredMethods())
                      .filter(method -> method.getReturnType().isAssignableFrom(AggregatedHttpResponse.class))
                      .collect(toImmutableList());

        for (Method method : webClientMethods) {
            final Optional<Method> found = blockingClientMethods.stream().filter(method0 -> {
                return method.getName().equals(method0.getName()) &&
                       Arrays.equals(method.getParameterTypes(), method0.getParameterTypes());
            }).findFirst();
            assertThat(found).isPresent();
        }
    }
}
