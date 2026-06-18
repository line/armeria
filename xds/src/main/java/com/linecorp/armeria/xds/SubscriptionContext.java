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
 */

package com.linecorp.armeria.xds;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.file.DirectoryWatchService;
import com.linecorp.armeria.xds.filter.FactoryContext;
import com.linecorp.armeria.xds.stream.SnapshotStream;

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;

interface SubscriptionContext extends FactoryContext {

    @Override
    default XdsResourceValidator validator() {
        return extensionRegistry().validator();
    }

    @Override
    default SnapshotStream<GenericSecretSnapshot> genericSecretStream(
            SdsSecretConfig sdsSecretConfig) {
        requireNonNull(sdsSecretConfig, "sdsSecretConfig");
        return new SecretStream(sdsSecretConfig, null, this)
                .switchMapEager(resource -> new GenericSecretStream(this, resource))
                .checkSubscribeOn(eventLoop());
    }

    void subscribe(ResourceNode<?> node);

    void unsubscribe(ResourceNode<?> node);

    ConfigSourceMapper configSourceMapper();

    XdsClusterManager clusterManager();

    DirectoryWatchService watchService();

    DataSourcePolicy dataSourcePolicy();

    BootstrapSecrets bootstrapSecrets();

    ResourceNodeMeterBinderFactory meterBinderFactory();

    XdsExtensionRegistry extensionRegistry();
}
