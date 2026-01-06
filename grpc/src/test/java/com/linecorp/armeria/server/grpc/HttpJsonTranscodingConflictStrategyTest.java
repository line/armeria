/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.server.grpc;

import static com.linecorp.armeria.server.grpc.HttpJsonTranscodingConflictStrategy.firstWins;
import static com.linecorp.armeria.server.grpc.HttpJsonTranscodingConflictStrategy.lastWins;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.api.HttpRule;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.it.grpc.HttpJsonTranscodingTestService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingService.TranscodingSpec;

class HttpJsonTranscodingConflictStrategyTest {

    @Test
    void firstWinsShouldKeepProtoRule() {
        final HttpRule rule = HttpRule
                .newBuilder()
                .setSelector("armeria.grpc.testing.HttpJsonTranscodingTestService.GetMessageV2")
                .setGet("/v2/additional/messages/{message_id}")
                .build();
        final HttpJsonTranscodingOptions options = HttpJsonTranscodingOptions.builder()
                                                                             .additionalHttpRules(rule)
                                                                             .conflictStrategy(firstWins())
                                                                             .build();
        final GrpcService grpcService = GrpcService.builder()
                                                   .addService(new HttpJsonTranscodingTestService())
                                                   .enableHttpJsonTranscoding(options)
                                                   .build();
        assertThat(grpcService).isInstanceOf(HttpJsonTranscodingService.class);
        final HttpJsonTranscodingService transcodingService = (HttpJsonTranscodingService) grpcService;
        final Map<Route, TranscodingSpec> routes = transcodingService.routeAndSpecs();
        assertThat(routes).containsKey(Route.builder()
                                            .path("/v2/messages/{message_id}")
                                            .methods(HttpMethod.GET)
                                            .build());
        assertThat(routes).doesNotContainKey(Route.builder()
                                                  .path("/v2/additional/messages/{message_id}")
                                                  .methods(HttpMethod.GET)
                                                  .build());
    }

    @Test
    void lastWinsShouldUseAdditionalRule() {
        final HttpRule rule = HttpRule
                .newBuilder()
                .setSelector("armeria.grpc.testing.HttpJsonTranscodingTestService.GetMessageV2")
                .setGet("/v2/additional/messages/{message_id}")
                .build();
        final HttpJsonTranscodingOptions options = HttpJsonTranscodingOptions.builder()
                                                                             .additionalHttpRules(rule)
                                                                             .conflictStrategy(lastWins())
                                                                             .build();
        final GrpcService grpcService = GrpcService.builder()
                                                   .addService(new HttpJsonTranscodingTestService())
                                                   .enableHttpJsonTranscoding(options)
                                                   .build();
        assertThat(grpcService).isInstanceOf(HttpJsonTranscodingService.class);
        final HttpJsonTranscodingService transcodingService = (HttpJsonTranscodingService) grpcService;
        final Map<Route, TranscodingSpec> routes = transcodingService.routeAndSpecs();
        assertThat(routes).doesNotContainKey(Route.builder()
                                                  .path("/v2/messages/{message_id}")
                                                  .methods(HttpMethod.GET)
                                                  .build());
        assertThat(routes).containsKey(Route.builder()
                                            .path("/v2/additional/messages/{message_id}")
                                            .methods(HttpMethod.GET)
                                            .build());
    }

    @Test
    void replacementShouldRemoveAdditionalBindings() {
        final HttpRule rule = HttpRule
                .newBuilder()
                .setSelector("armeria.grpc.testing.HttpJsonTranscodingTestService.AdditionalBinding")
                .setGet("/v2/additional_binding")
                .build();
        final HttpJsonTranscodingOptions options = HttpJsonTranscodingOptions.builder()
                                                                             .additionalHttpRules(rule)
                                                                             .conflictStrategy(lastWins())
                                                                             .build();
        final GrpcService grpcService = GrpcService.builder()
                                                   .addService(new HttpJsonTranscodingTestService())
                                                   .enableHttpJsonTranscoding(options)
                                                   .build();
        assertThat(grpcService).isInstanceOf(HttpJsonTranscodingService.class);
        final HttpJsonTranscodingService transcodingService = (HttpJsonTranscodingService) grpcService;
        final Map<Route, TranscodingSpec> routes = transcodingService.routeAndSpecs();
        assertThat(routes).doesNotContainKey(Route.builder()
                                                  .path("/v1/additional_binding")
                                                  .methods(HttpMethod.POST)
                                                  .build());
        assertThat(routes).doesNotContainKey(Route.builder()
                                                  .path("/v1/alternate_additional_binding")
                                                  .methods(HttpMethod.PUT)
                                                  .build());
        assertThat(routes).containsKey(Route.builder()
                                            .path("/v2/additional_binding")
                                            .methods(HttpMethod.GET)
                                            .build());
    }

    @Test
    void crossMethodRouteConflictShouldThrow() {
        final HttpRule conflictingRule = HttpRule
                .newBuilder()
                .setSelector("armeria.grpc.testing.HttpJsonTranscodingTestService.GetMessageV2")
                .setGet("/v1/{name=messages/*}")
                .build();
        final HttpJsonTranscodingOptions options =
                HttpJsonTranscodingOptions.builder()
                                          .additionalHttpRules(conflictingRule)
                                          .conflictStrategy(lastWins())
                                          .build();
        assertThatThrownBy(() -> {
            GrpcService.builder()
                       .addService(new HttpJsonTranscodingTestService())
                       .enableHttpJsonTranscoding(options)
                       .build();
        }).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void strategyReturningDifferentRuleShouldThrow() {
        final HttpRule differentRule = HttpRule
                .newBuilder()
                .setSelector("armeria.grpc.testing.HttpJsonTranscodingTestService.GetMessageV2")
                .setGet("/v2/different/messages/{message_id}")
                .build();
        final HttpJsonTranscodingConflictStrategy differentStrategy = (selector, oldRule, newRule) -> {
            return differentRule;
        };
        final HttpRule conflictingRule = HttpRule
                .newBuilder()
                .setSelector("armeria.grpc.testing.HttpJsonTranscodingTestService.GetMessageV2")
                .setGet("/v2/additional/messages/{message_id}")
                .build();
        final HttpJsonTranscodingOptions options =
                HttpJsonTranscodingOptions.builder()
                                          .additionalHttpRules(conflictingRule)
                                          .conflictStrategy(differentStrategy)
                                          .build();
        assertThatThrownBy(() -> {
            GrpcService.builder()
                       .addService(new HttpJsonTranscodingTestService())
                       .enableHttpJsonTranscoding(options)
                       .build();
        }).isInstanceOf(IllegalStateException.class);
    }
}
