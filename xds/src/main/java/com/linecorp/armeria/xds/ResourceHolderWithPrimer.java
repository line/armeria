/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.xds;

import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

/**
 * A resource holder that has its primer.
 *
 * @param <SELF> the type of this
 * @param <T> the type of the resource. Can be {@link Listener}, {@link RouteConfiguration}, {@link Cluster} or
 *            {@link ClusterLoadAssignment}.
 * @param <U> the type of the primer
 */
abstract class ResourceHolderWithPrimer
        <SELF extends ResourceHolderWithPrimer<SELF, T, U>, T extends Message, U extends Message>
        implements ResourceHolder<T> {

    abstract SELF withPrimer(@Nullable ResourceHolder<U> primer);

    @Nullable
    abstract ResourceHolder<U> primer();
}
