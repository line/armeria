/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

public class DynamicEndpointGroupTest {

    @Test
    public void updateEndpoints() {
        DynamicEndpointGroup endpointGroup = new DynamicEndpointGroup();
        AtomicInteger updateListenerCalled = new AtomicInteger(0);
        endpointGroup.addListener(l -> updateListenerCalled.incrementAndGet());

        assertThat(updateListenerCalled.get()).isEqualTo(0);
        endpointGroup.setEndpoints(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                    Endpoint.of("127.0.0.1", 2222)));
        assertThat(updateListenerCalled.get()).isEqualTo(1);

        endpointGroup.addEndpoint(Endpoint.of("127.0.0.1", 3333));
        assertThat(updateListenerCalled.get()).isEqualTo(2);
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                                         Endpoint.of("127.0.0.1", 2222),
                                                                         Endpoint.of("127.0.0.1", 3333)));

        endpointGroup.removeEndpoint(Endpoint.of("127.0.0.1", 2222));
        assertThat(updateListenerCalled.get()).isEqualTo(3);
        assertThat(endpointGroup.endpoints()).isEqualTo(ImmutableList.of(Endpoint.of("127.0.0.1", 1111),
                                                                         Endpoint.of("127.0.0.1", 3333)));
    }
}
