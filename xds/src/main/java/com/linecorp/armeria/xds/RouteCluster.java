/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.xds;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.core.v3.Metadata;

/**
 * The result of route resolution via {@link RouteEntry#resolve()}.
 * Contains the resolved cluster, metadata match criteria, and pre-built filter chains.
 */
@UnstableApi
public interface RouteCluster {

    /**
     * Returns the selected {@link ClusterSnapshot}.
     */
    ClusterSnapshot clusterSnapshot();

    /**
     * Returns the metadata match criteria.
     */
    Metadata metadataMatch();

    /**
     * Returns the pre-built {@link HttpClient} chain for this route.
     */
    HttpClient httpClient();

    /**
     * Returns the pre-built {@link RpcClient} chain for this route.
     */
    RpcClient rpcClient();
}
