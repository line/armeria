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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;


/**
 * Provides the Athenz resource string from a specific HTTP header.
 *
 * <p>This provider extracts the resource value from the request header specified by the header name.
 * If the header is not present or empty, an empty string is returned and logged at debug level.
 *
 * <p>Example:
 * <pre>{@code
 * AthenzService.builder(ztsBaseClient)
 *              .resourceProvider(AthenzResourceProvider.ofHeader("X-Athenz-Resource"))
 *              .action("read")
 *              .newDecorator();
 * }</pre>
 *
 * <p>If a request includes the header {@code X-Athenz-Resource: resourceId},
 * the resource will be {@code "resourceId"}.
 */
@UnstableApi
public class HeaderAthenzResourceProvider implements AthenzResourceProvider {

    private static final Logger logger = LoggerFactory.getLogger(HeaderAthenzResourceProvider.class);

    private final String headerName;

    /**
     * Creates a new instance that extracts the Athenz resource from the specified header.
     *
     * @param headerName the name of the HTTP header to extract the resource from
     */
    protected HeaderAthenzResourceProvider(String headerName) {
        requireNonNull(headerName, "headerName");
        checkArgument(!headerName.isEmpty(), "headerName must not be empty");
        this.headerName = headerName;
    }

    @Override
    public CompletableFuture<String> provide(ServiceRequestContext ctx, HttpRequest req) {
        final String value = req.headers().get(headerName);
        if (value == null) {
            logger.debug("Header '{}' not found in the request", headerName);
            return CompletableFuture.completedFuture("");
        }
        return CompletableFuture.completedFuture(value);
    }
}
