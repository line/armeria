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
package com.linecorp.armeria.client.nacos;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.Flags;

class NacosEndpointGroupBuilderTest {

    @Test
    void selectionTimeoutDefault() {
        try (NacosEndpointGroup group = NacosEndpointGroup.of(URI.create("http://127.0.0.1/node"),
                                                                "testService")) {
            assertThat(group.selectionTimeoutMillis()).isEqualTo(Flags.defaultResponseTimeoutMillis());
        }
    }

    @Test
    void selectionTimeoutCustom() {
        try (NacosEndpointGroup group =
                     NacosEndpointGroup.builder(URI.create("http://127.0.0.1/node"), "testService")
                                        .selectionTimeoutMillis(4000)
                                        .build()) {
            assertThat(group.selectionTimeoutMillis()).isEqualTo(4000);
        }
    }
}
