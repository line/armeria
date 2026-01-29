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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServiceRequestContext;

final class HeaderAthenzResourceProvider implements AthenzResourceProvider {

    private final String headerName;

    HeaderAthenzResourceProvider(String headerName) {
        requireNonNull(headerName, "headerName");
        checkArgument(!headerName.isEmpty(), "headerName must not be empty");
        this.headerName = headerName;
    }

    @Override
    public CompletableFuture<String> provide(ServiceRequestContext ctx, HttpRequest req) {
        final String value = req.headers().get(headerName, "");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Missing required header: " + headerName);
        }
        return UnmodifiableFuture.completedFuture(value);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("headerName", headerName)
                          .toString();
    }
}
