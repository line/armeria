/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.consul;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.Flags;

class ConsulEndpointGroupBuilderTest {

    @Test
    void selectionTimeout_default() {
        try (ConsulEndpointGroup group = ConsulEndpointGroup.of(URI.create("http://127.0.0.1/node"),
                                                                "my-service")) {
            assertThat(group.selectionTimeoutMillis()).isEqualTo(Flags.defaultResponseTimeoutMillis());
        }
    }

    @Test
    void selectionTimeout_custom() {
        try (ConsulEndpointGroup group =
                     ConsulEndpointGroup.builder(URI.create("http://127.0.0.1/node"), "my-service")
                                        .selectionTimeoutMillis(4000)
                                        .build()) {
            assertThat(group.selectionTimeoutMillis()).isEqualTo(4000);
        }
    }
}
