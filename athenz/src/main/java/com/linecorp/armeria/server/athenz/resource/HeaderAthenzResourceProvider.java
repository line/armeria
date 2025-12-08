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
import com.linecorp.armeria.server.ServiceRequestContext;

import java.util.concurrent.CompletableFuture;

/**
 * Provides the Athenz resource string from a specific HTTP header.
 *
 * <p>This provider extracts the resource value from the request header specified by the {@code headerName}.
 * If the header is not present or empty, an empty string is returned.
 *
 * <p>Example:
 * <pre>{@code
 * AthenzResourceProvider provider = new HeaderAthenzResourceProvider("X-Athenz-Resource");
 * AthenzService.builder(ztsBaseClient)
 *              .resourceProvider(provider)
 *              .action("read")
 *              .newDecorator();
 * }</pre>
 */
public class HeaderAthenzResourceProvider implements AthenzResourceProvider {

    private final String headerName;


    /**
     * Creates a new instance that extracts the Athenz resource from the specified header.
     *
     * @param headerName the name of the HTTP header to extract the resource from
     */
    public HeaderAthenzResourceProvider(String headerName) {
        this.headerName = headerName;
    }

    @Override
    public CompletableFuture<String> provide(ServiceRequestContext ctx, HttpRequest req) {
        final String value = req.headers().get(headerName);
        return CompletableFuture.completedFuture(value != null ? value : "");
    }
}
