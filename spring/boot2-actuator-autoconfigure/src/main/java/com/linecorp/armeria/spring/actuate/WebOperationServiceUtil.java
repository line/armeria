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

package com.linecorp.armeria.spring.actuate;

import static com.linecorp.armeria.spring.actuate.ArmeriaSpringActuatorAutoConfiguration.addOperationService;

import java.util.List;
import java.util.Set;

import org.springframework.boot.actuate.endpoint.OperationArgumentResolver;
import org.springframework.boot.actuate.endpoint.ProducibleOperationArgumentResolver;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.WebOperation;
import org.springframework.boot.actuate.endpoint.web.WebOperationRequestPredicate;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.actuate.health.AdditionalHealthEndpointPath;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.cors.CorsServiceBuilder;

final class WebOperationServiceUtil {

    static OperationArgumentResolver acceptHeadersResolver(HttpHeaders headers) {
        return new ProducibleOperationArgumentResolver(() -> headers.getAll(HttpHeaderNames.ACCEPT));
    }

    static OperationArgumentResolver namespaceResolver(boolean server) {
        return OperationArgumentResolver.of(WebServerNamespace.class,
                                            () -> server ? WebServerNamespace.SERVER
                                                         : WebServerNamespace.MANAGEMENT);
    }

    static void addAdditionalPath(ServerBuilder sb, List<Integer> exposedPorts,
                                  ExposableWebEndpoint endpoint,
                                  SimpleHttpCodeStatusMapper statusMapper,
                                  @Nullable CorsServiceBuilder cors, HealthEndpointGroups groups) {
        for (WebOperation operation : endpoint.getOperations()) {
            final WebOperationRequestPredicate predicate = operation.getRequestPredicate();
            final String matchAllRemainingPathSegmentsVariable =
                    predicate.getMatchAllRemainingPathSegmentsVariable();
            // group operation has matchAllRemainingPathSegmentsVariable.
            // e.g. /actuator/health/{*path}
            // We can send a request to /actuator/health/foo if the group name is foo.
            //
            // We have to check if the group has additional path or not.
            // e.g. management:
            //        endpoint:
            //          health:
            //            group:
            //              foo:
            //                include: ping
            //                additional-path: "management:/foohealth"
            if (matchAllRemainingPathSegmentsVariable != null) {
                if (!exposedPorts.isEmpty()) {
                    final Set<HealthEndpointGroup> additionalGroups = groups.getAllWithAdditionalPath(
                            WebServerNamespace.MANAGEMENT);
                    addAdditionalPath(sb, exposedPorts, statusMapper, operation, predicate, additionalGroups,
                                      cors);
                }

                final Set<HealthEndpointGroup> additionalGroups = groups.getAllWithAdditionalPath(
                        WebServerNamespace.SERVER);
                addAdditionalPath(sb, ImmutableList.of(), statusMapper, operation, predicate, additionalGroups,
                                  cors);
            }
        }
    }

    static void addAdditionalPath(ServerBuilder sb, List<Integer> exposedPorts,
                                          SimpleHttpCodeStatusMapper statusMapper, WebOperation operation,
                                          WebOperationRequestPredicate predicate,
                                          Set<HealthEndpointGroup> additionalGroups,
                                          @Nullable CorsServiceBuilder cors) {
        for (HealthEndpointGroup group : additionalGroups) {
            final AdditionalHealthEndpointPath additionalPath = group.getAdditionalPath();
            if (additionalPath != null) {
                final String path = additionalPath.getValue();
                addOperationService(sb, exposedPorts, operation, statusMapper, predicate,
                                    path, ImmutableMap.of("path", new String[] { path }), cors);
            }
        }
    }

    private WebOperationServiceUtil() {}
}
