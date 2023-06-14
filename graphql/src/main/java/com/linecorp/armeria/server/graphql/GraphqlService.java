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

import org.dataloader.DataLoaderRegistry;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.HttpService;

import graphql.GraphQL;

/**
 * An {@link HttpService} that implements the <a href="https://www.graphql-java.com/">GraphQL</a>.
 */
@UnstableApi
@FunctionalInterface
public interface GraphqlService extends HttpService {

    /**
     * Returns a new {@link GraphqlServiceBuilder}.
     */
    static GraphqlServiceBuilder builder() {
        return new GraphqlServiceBuilder();
    }

    /**
     * Returns a new {@link GraphqlService}.
     */
    static GraphqlService of(GraphQL graphQL) {
        return new DefaultGraphqlService(graphQL, ctx -> new DataLoaderRegistry(), false,
                                         GraphqlErrorHandler.of());
    }
}
