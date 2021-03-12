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

package com.linecorp.armeria.server.graphql;

import graphql.schema.idl.RuntimeWiring;

/**
 * Interface used to configure a scala type, data fetcher, directives, etc on the GraphQL service.
 */
@FunctionalInterface
public interface RuntimeWiringConfigurator {

    /**
     * Configures the service using the specified {@link RuntimeWiring.Builder}.
     */
    void configure(RuntimeWiring.Builder builder);
}
