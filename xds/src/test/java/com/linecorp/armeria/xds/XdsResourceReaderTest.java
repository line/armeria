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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.protobuf.util.JsonFormat.TypeRegistry;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

class XdsResourceReaderTest {

    @Test
    void registersScannedTypes() {
        final TypeRegistry registry = XdsResourceReader.typeRegistry();
        assertThat(registry.find(Cluster.getDescriptor().getFullName()))
                .isEqualTo(Cluster.getDescriptor());
        assertThat(registry.find(HttpConnectionManager.getDescriptor().getFullName()))
                .isEqualTo(HttpConnectionManager.getDescriptor());
        assertThat(registry.find(com.github.udpa.udpa.type.v1.TypedStruct.getDescriptor().getFullName()))
                .isEqualTo(com.github.udpa.udpa.type.v1.TypedStruct.getDescriptor());
        assertThat(registry.find(com.github.xds.type.v3.TypedStruct.getDescriptor().getFullName()))
                .isEqualTo(com.github.xds.type.v3.TypedStruct.getDescriptor());
    }
}
