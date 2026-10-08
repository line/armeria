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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.WebClient;

class XdsBootstrapRegistryTest {

    private static final String BOOTSTRAP_NAME = "registry-test";

    @AfterEach
    void tearDown() {
        XdsBootstrapRegistry.deregister(BOOTSTRAP_NAME);
    }

    @Test
    void clientCanBeCreatedBeforeBootstrapRegistered() {
        final WebClient client = WebClient.of("xds://" + BOOTSTRAP_NAME + "/listener1");
        assertThat(client).isNotNull();
    }

    @Test
    void executeFailsBeforeBootstrapRegistered() {
        final WebClient client = WebClient.of("xds://" + BOOTSTRAP_NAME + "/listener1");
        assertThatThrownBy(() -> client.blocking().get("/hello"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("No XdsBootstrap registered with name");
    }
}
