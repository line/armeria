/*
 * Copyright 2016 LINE Corporation
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

import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;

public class EndpointGroupTest {
    @Test
    public void orElse() throws Exception {
        EndpointGroup emptyEndpointGroup = new StaticEndpointGroup();
        EndpointGroup endpointGroup1 = new StaticEndpointGroup(Endpoint.of("127.0.0.1", 1234));
        EndpointGroup endpointGroup2 = new StaticEndpointGroup(Endpoint.of("127.0.0.1", 2345));

        assertThat(emptyEndpointGroup.orElse(endpointGroup2).endpoints())
                .isEqualTo(endpointGroup2.endpoints());
        assertThat(endpointGroup1.orElse(endpointGroup2).endpoints())
                .isEqualTo(endpointGroup1.endpoints());
    }
}
