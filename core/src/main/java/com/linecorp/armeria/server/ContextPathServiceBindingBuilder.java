/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder class for binding an {@link HttpService} fluently. This class can be instantiated through
 * {@link ServerBuilder#contextPath(String...)}.
 *
 * <p>Call {@link #build(HttpService)} to build the {@link HttpService} and return to the {@link ServerBuilder}
 * or {@link VirtualHostBuilder}.
 *
 * <pre>{@code
 * Server.builder()
 *       .contextPath("/v1", "/v2")
 *       .route()
 *       .get("/service1")
 *       .build(service1) // served under "/v1/service1" and "/v2/service1"
 * }</pre>
 */
@UnstableApi
public final class ContextPathServiceBindingBuilder
        extends AbstractContextPathServiceBindingBuilder<ContextPathServiceBindingBuilder,
        ContextPathServicesBuilder> {

    ContextPathServiceBindingBuilder(ContextPathServicesBuilder builder) {
        super(builder);
    }
}
