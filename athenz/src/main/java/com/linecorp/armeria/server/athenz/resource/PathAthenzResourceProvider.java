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
 *
 */

package com.linecorp.armeria.server.athenz.resource;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServiceRequestContext;

import java.util.concurrent.CompletableFuture;

/**
 * Provides the Athenz resource string from the request path.
 *
 * <p>This provider extracts the resource value directly from the request path.
 * The path returned includes the full URI path after the host.
 *
 * <p>Example:
 * <pre>{@code
 * AthenzResourceProvider provider = new PathAthenzResourceProvider();
 * AthenzService.builder(ztsBaseClient)
 *              .resourceProvider(provider, resourceTagValue)
 *              .action("read")
 *              .newDecorator();
 * }</pre>
 *
 * <p>If a request is made to {@code "/api/users/123"}, the resource will be {@code "/api/users/123"}.
 */
@UnstableApi
public final class PathAthenzResourceProvider implements AthenzResourceProvider {

    @Override
    public CompletableFuture<String> provide(ServiceRequestContext ctx, HttpRequest req) {
        return CompletableFuture.completedFuture(req.path());
    }
}