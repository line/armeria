/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class ClientAddressSourceTest {

    @Test
    void testHashCodeAndEquals() {
        assertThat(ClientAddressSource.ofHeader("custom-header-name"))
                .isEqualTo(ClientAddressSource.ofHeader("custom-header-name"));
        assertThat(ClientAddressSource.ofProxyProtocol())
                .isEqualTo(ClientAddressSource.ofHeader("PROXY_PROTOCOL"));
        assertThat(ClientAddressSource.ofProxyProtocol().hashCode())
                .isEqualTo(ClientAddressSource.ofHeader("PROXY_PROTOCOL").hashCode());
        assertThat(ClientAddressSource.ofHeader("PROXY_PROTOCOL").isProxyProtocol()).isTrue();
    }
}
