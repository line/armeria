/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.spring.web.version.reactive;

import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.AbstractReactiveHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.webflux.autoconfigure.error.AbstractErrorWebExceptionHandler;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.linecorp.armeria.internal.testing.AnticipatedException;

import reactor.core.publisher.Mono;

/**
 * In a separate package to avoid automatic component scanning from {@link SpringBootTest}.
 */
public final class VersionSpecificTestUtil {

    @Configuration
    @EnableConfigurationProperties(ServerProperties.class)
    public static class VersionSpecificConfiguration {
    }

    @Configuration
    @Import(VersionSpecificConfiguration.class)
    public static class VersionSpecificErrorConfiguration {
        @Component
        static class CustomExceptionHandler extends AbstractErrorWebExceptionHandler {

            CustomExceptionHandler(ErrorAttributes errorAttributes,
                                   WebProperties webProperties,
                                   ApplicationContext applicationContext,
                                   ServerCodecConfigurer serverCodecConfigurer) {
                super(errorAttributes, webProperties.getResources(), applicationContext);
                setMessageWriters(serverCodecConfigurer.getWriters());
                setMessageReaders(serverCodecConfigurer.getReaders());
            }

            @Override
            protected RouterFunction<ServerResponse> getRoutingFunction(ErrorAttributes errorAttributes) {
                return RouterFunctions.route(
                        RequestPredicates.all(), req -> {
                            final Throwable error = errorAttributes.getError(req);
                            if (error instanceof AnticipatedException) {
                                return ServerResponse.status(HttpStatus.BAD_REQUEST)
                                                     .bodyValue("CustomExceptionHandler");
                            }
                            return ServerResponse.ok().build();
                        }
                );
            }
        }
    }

    public static class TestReactiveHealthIndicator extends AbstractReactiveHealthIndicator {

        private final AtomicBoolean healthy;

        public TestReactiveHealthIndicator(AtomicBoolean healthy) {
            this.healthy = healthy;
        }

        @Override
        protected Mono<Health> doHealthCheck(Health.Builder builder) {
            return Mono.fromSupplier(() -> {
                if (healthy.get()) {
                    return builder.up().build();
                }
                return builder.down().build();
            });
        }
    }

    public static Iterable<Entry<String, List<String>>> toHeaderEntries(HttpHeaders httpHeaders) {
        return httpHeaders.headerSet();
    }

    private VersionSpecificTestUtil() {}
}
