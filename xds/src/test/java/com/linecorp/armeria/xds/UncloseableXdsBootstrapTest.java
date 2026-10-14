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

import static com.linecorp.armeria.xds.XdsTestResources.createStaticCluster;
import static com.linecorp.armeria.xds.XdsTestResources.loadAssignment;
import static com.linecorp.armeria.xds.XdsTestResources.staticBootstrap;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

class UncloseableXdsBootstrapTest {

    @Test
    void closeShouldNotCloseDelegate() {
        final Bootstrap bootstrap = staticBootstrap(
                XdsTestResources.exampleListener("listener1", "route1", "cluster1"),
                createStaticCluster("cluster1", loadAssignment("cluster1", "127.0.0.1", 8080)));
        final XdsBootstrapImpl delegate = (XdsBootstrapImpl) XdsBootstrap.of(bootstrap);
        final UncloseableXdsBootstrap uncloseable = new UncloseableXdsBootstrap(delegate);

        try {
            uncloseable.close();
            assertThat(delegate.isClosed()).isFalse();
        } finally {
            delegate.close();
        }
        assertThat(delegate.isClosed()).isTrue();
    }
}
