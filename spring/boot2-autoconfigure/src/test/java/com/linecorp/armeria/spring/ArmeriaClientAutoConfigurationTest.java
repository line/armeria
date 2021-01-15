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

package com.linecorp.armeria.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.inject.Inject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.spring.ArmeriaClientAutoConfigurationTest.TestConfiguration;

/**
 * This uses {@link ArmeriaClientAutoConfiguration} for integration tests.
 * application-autoConfTest.yml will be loaded with minimal settings to make it work.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles({ "local", "autoConfTest" })
@DirtiesContext
public class ArmeriaClientAutoConfigurationTest {

    static Queue<Integer> queue = new ArrayDeque<>();

    static DecoratingHttpClientFunction newDecorator(int id) {
        return (delegate, ctx, req) -> {
            queue.add(id);
            return delegate.execute(ctx, req);
        };
    }

    @SpringBootApplication
    public static class TestConfiguration {
        @Bean
        public Consumer<ServerBuilder> customizer() {
            return sb -> sb.service("/customizer", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }

        @Bean
        @Order(1)
        public ArmeriaClientConfigurator clientConfigurator1() {
            return builder -> builder.decorator(newDecorator(1));
        }

        @Bean
        @Order(-1)
        public ArmeriaClientConfigurator clientConfigurator2() {
            return builder -> builder.decorator(newDecorator(2));
        }

        @Bean
        @Order(0)
        public ArmeriaClientConfigurator clientConfigurator3() {
            return builder -> builder.decorator(newDecorator(3));
        }
    }

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @LocalArmeriaPort
    private Integer port;

    @Inject
    private Supplier<WebClientBuilder> webClientBuilder;

    private String newUrl(String scheme) {
        return scheme + "://127.0.0.1:" + port;
    }

    @Test
    public void test() throws Exception {
        final WebClient webClient = webClientBuilder.get().build();
        final AggregatedHttpResponse response = webClient.get(newUrl("h1c") + "/customizer")
                                                         .aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(queue).containsExactlyElementsOf(ImmutableList.of(1, 3, 2));
    }
}
