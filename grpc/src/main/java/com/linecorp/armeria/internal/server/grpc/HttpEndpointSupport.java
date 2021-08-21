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

package com.linecorp.armeria.internal.server.grpc;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.grpc.GrpcService;

/**
 * Marks the {@link GrpcService} additionally support HTTP/JSON API.
 */
public interface HttpEndpointSupport {
    /**
     * Returns a {@link HttpEndpointSpecification} of the specified {@link Route},
     * which is used to generate {@link DocService} by {@link GrpcDocServicePlugin}.
     * Returns {@code null} if an HTTP API is not provided.
     */
    @Nullable
    HttpEndpointSpecification httpEndpointSpecification(Route route);
}
