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
package com.linecorp.armeria.common.zookeeper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroupException;

public class NodeValueCodecTest {
    @Test
    public void convert() {
        assertThat(NodeValueCodec.DEFAULT
                           .decodeAll("foo.com, bar.com:8080, 10.0.2.15:0:500, 192.168.1.2:8443:700"))
                .containsExactlyInAnyOrder(Endpoint.of("foo.com"),
                                           Endpoint.of("bar.com", 8080),
                                           Endpoint.of("10.0.2.15").withWeight(500),
                                           Endpoint.of("192.168.1.2", 8443, 700));
        assertThatThrownBy(() -> NodeValueCodec.DEFAULT
                .decodeAll("http://foo.com:8001, bar.com:8002"))
                .isInstanceOf(EndpointGroupException.class);
    }
}
