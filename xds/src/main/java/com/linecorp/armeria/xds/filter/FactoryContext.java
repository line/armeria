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

package com.linecorp.armeria.xds.filter;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.GenericSecretSnapshot;
import com.linecorp.armeria.xds.XdsExtensionRegistry;
import com.linecorp.armeria.xds.XdsResourceValidator;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.concurrent.EventExecutor;

/**
 * Provides runtime infrastructure to xDS extension factories (HTTP filters, transport sockets,
 * network filters, etc.) during creation.
 */
@UnstableApi
public interface FactoryContext {

    /**
     * Returns the original xDS {@link Bootstrap} configuration.
     */
    Bootstrap bootstrap();

    /**
     * Returns the event loop used for scheduling and executing asynchronous operations.
     */
    EventExecutor eventLoop();

    /**
     * Returns the {@link MeterRegistry} for recording metrics.
     */
    MeterRegistry meterRegistry();

    /**
     * Returns the {@link MeterIdPrefix} for metric naming.
     */
    MeterIdPrefix meterIdPrefix();

    /**
     * Returns the {@link XdsResourceValidator} for validating and unpacking protobuf messages.
     */
    XdsResourceValidator validator();

    /**
     * Returns the {@link XdsExtensionRegistry} for looking up extension factories.
     */
    XdsExtensionRegistry extensionRegistry();

    /**
     * Creates a reactive {@link SnapshotStream} of {@link GenericSecretSnapshot} that fetches
     * a generic secret via SDS or bootstrap, resolves its {@link
     * io.envoyproxy.envoy.config.core.v3.DataSource DataSource}, and emits snapshots containing
     * the resolved credential value.
     *
     * @param sdsSecretConfig the SDS secret configuration describing which secret to fetch
     */
    SnapshotStream<GenericSecretSnapshot> genericSecretStream(SdsSecretConfig sdsSecretConfig);

    /**
     * Creates a reactive {@link SnapshotStream} of {@link ClusterSnapshot} for the given cluster name.
     * The stream resolves the cluster via CDS (or static bootstrap clusters), including its
     * endpoints, transport sockets, and load balancer, and emits snapshots whenever any of
     * these change.
     *
     * @param clusterName the name of the cluster to watch
     */
    SnapshotStream<ClusterSnapshot> clusterStream(String clusterName);
}
