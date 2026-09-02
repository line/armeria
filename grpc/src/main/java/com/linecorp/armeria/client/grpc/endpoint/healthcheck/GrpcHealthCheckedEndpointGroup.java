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
package com.linecorp.armeria.client.grpc.endpoint.healthcheck;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Provides a way to build a health checked {@link EndpointGroup} whose health comes from
 * a standard gRPC health check service.
 */
@UnstableApi
public final class GrpcHealthCheckedEndpointGroup {

    /**
     * Returns a {@link GrpcHealthCheckedEndpointGroupBuilder} that builds a health checked
     * endpoint group with the specified {@link EndpointGroup} and {@link GrpcHealthCheckMethod}.
     *
     * @param delegate the {@link EndpointGroup} that provides the candidate {@link Endpoint}s
     * @param healthCheckMethod the gRPC health check method used to check the health of the candidates
     */
    public static GrpcHealthCheckedEndpointGroupBuilder builder(EndpointGroup delegate,
                                                                  GrpcHealthCheckMethod healthCheckMethod) {
        return new GrpcHealthCheckedEndpointGroupBuilder(delegate, healthCheckMethod);
    }

    private GrpcHealthCheckedEndpointGroup() {}
}
