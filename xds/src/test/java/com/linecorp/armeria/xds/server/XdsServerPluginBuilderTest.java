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

package com.linecorp.armeria.xds.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.xds.XdsBootstrap;

class XdsServerPluginBuilderTest {

    @Test
    void portMustHaveBothHttpAndHttps() {
        final XdsBootstrap bootstrap = mock(XdsBootstrap.class);
        final XdsServerPluginBuilder builder = XdsServerPlugin.builder(bootstrap, "test-listener");
        assertThatThrownBy(() -> builder.port(new ServerPort(8080, SessionProtocol.HTTP)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must support both HTTP and HTTPS");
    }

    @Test
    void portWithBothProtocolsAccepted() {
        final XdsBootstrap bootstrap = mock(XdsBootstrap.class);
        final XdsServerPluginBuilder builder = XdsServerPlugin.builder(bootstrap, "test-listener");
        // Should not throw
        builder.port(new ServerPort(8080, SessionProtocol.HTTP, SessionProtocol.HTTPS));
    }

    @Test
    void readyTimeoutNegativeThrows() {
        final XdsBootstrap bootstrap = mock(XdsBootstrap.class);
        final XdsServerPluginBuilder builder = XdsServerPlugin.builder(bootstrap, "test-listener");
        assertThatThrownBy(() -> builder.readyTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("readyTimeout");
    }

    @Test
    void readyTimeoutZeroAllowed() {
        final XdsBootstrap bootstrap = mock(XdsBootstrap.class);
        final XdsServerPluginBuilder builder = XdsServerPlugin.builder(bootstrap, "test-listener");
        // Should not throw
        builder.readyTimeout(Duration.ZERO);
    }

    @Test
    void httpsOnlyPortRejected() {
        final XdsBootstrap bootstrap = mock(XdsBootstrap.class);
        final XdsServerPluginBuilder builder = XdsServerPlugin.builder(bootstrap, "test-listener");
        assertThatThrownBy(() -> builder.port(new ServerPort(8443, SessionProtocol.HTTPS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must support both HTTP and HTTPS");
    }

    @Test
    void builderCreatedWithPorts() {
        final XdsBootstrap bootstrap = mock(XdsBootstrap.class);
        // Builder created with int... ports — should not throw during builder creation.
        final XdsServerPluginBuilder builder = XdsServerPlugin.builder(bootstrap, "test-listener", 0, 0);
        assertThat(builder).isNotNull();
    }
}
