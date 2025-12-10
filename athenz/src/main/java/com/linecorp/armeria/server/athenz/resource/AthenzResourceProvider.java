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

import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.core.JsonPointer;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Provides the Athenz resource string from an HTTP request.
 *
 * <p>This is the core interface for extracting resource identifiers from various parts of an HTTP request
 * (path, headers, JSON body fields, etc.) for Athenz authorization.
 *
 * <p>Built-in implementations are available through static factory methods:
 * <ul>
 *   <li>{@link #ofPath()} - Extracts resource from the request path</li>
 *   <li>{@link #ofPath(boolean)} - Extracts resource from the request path, optionally including query parameters</li>
 *   <li>{@link #ofHeader(String)} - Extracts resource from a specific HTTP header</li>
 *   <li>{@link #ofJsonField(String)} - Extracts resource from a JSON body field</li>
 *   <li>{@link #ofJsonField(JsonPointer)} - Extracts resource from a JSON body field using JSON Pointer</li>
 * </ul>
 *
 * <p>Example usage with path-based resource:
 * <pre>{@code
 * AthenzService.builder(ztsBaseClient)
 *              .resourceProvider(AthenzResourceProvider.ofPath())
 *              .action("read")
 *              .newDecorator();
 * }</pre>
 *
 * <p>Example usage with header-based resource:
 * <pre>{@code
 * AthenzService.builder(ztsBaseClient)
 *              .resourceProvider(AthenzResourceProvider.ofHeader("X-Resource-Id"))
 *              .action("write")
 *              .newDecorator();
 * }</pre>
 *
 * <p>Custom implementations can be created by implementing the {@link #provide(ServiceRequestContext, HttpRequest)}
 * method to extract resources from any part of the request.
 */
@UnstableApi
@FunctionalInterface
public interface AthenzResourceProvider {

    CompletableFuture<String> provide(ServiceRequestContext ctx, HttpRequest req);

    static AthenzResourceProvider ofPath() {
        return PathAthenzResourceProvider.INSTANCE;
    }

    static AthenzResourceProvider ofPath(boolean includeQuery) {
        if (includeQuery) {
            return PathAthenzResourceProvider.QUERY_INSTANCE;
        } else {
            return PathAthenzResourceProvider.INSTANCE;
        }
    }

    static AthenzResourceProvider ofHeader(String headerName) {
        return new HeaderAthenzResourceProvider(headerName);
    }

    static AthenzResourceProvider ofJsonField(JsonPointer jsonPointer) {
        return new JsonBodyFieldAthenzResourceProvider(jsonPointer);
    }

    static AthenzResourceProvider ofJsonField(String jsonFieldName) {
        return new JsonBodyFieldAthenzResourceProvider(jsonFieldName);
    }

}
