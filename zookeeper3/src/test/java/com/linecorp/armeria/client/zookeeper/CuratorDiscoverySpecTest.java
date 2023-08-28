/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.client.zookeeper;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.curator.x.discovery.ServiceInstance;
import org.apache.curator.x.discovery.ServiceType;
import org.apache.curator.x.discovery.UriSpec;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.internal.common.zookeeper.CuratorXNodeValueCodec;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;

@GenerateNativeImageTrace
class CuratorDiscoverySpecTest {

    @Test
    void decode() {
        ZooKeeperDiscoverySpec spec = ZooKeeperDiscoverySpec.curator("foo");
        ServiceInstance<?> instance = serviceInstance(false);
        Endpoint endpoint = spec.decode(CuratorXNodeValueCodec.INSTANCE.encode(instance));
        assertThat(endpoint).isNull(); // enabled is false;

        instance = serviceInstance(true);
        endpoint = spec.decode(CuratorXNodeValueCodec.INSTANCE.encode(instance));
        assertThat(endpoint).isEqualTo(Endpoint.of("foo.com", 100));

        spec = ZooKeeperDiscoverySpec.builderForCurator("foo").useSsl(true).build();
        endpoint = spec.decode(CuratorXNodeValueCodec.INSTANCE.encode(instance));
        assertThat(endpoint).isEqualTo(Endpoint.of("foo.com", 200)); // useSsl

        final Endpoint bar = Endpoint.of("bar");
        spec = ZooKeeperDiscoverySpec.builderForCurator("foo")
                                     .converter(serviceInstance -> bar)
                                     .build();
        endpoint = spec.decode(CuratorXNodeValueCodec.INSTANCE.encode(instance));
        assertThat(endpoint).isSameAs(bar); // Use converter.
    }

    private static ServiceInstance<?> serviceInstance(boolean enabled) {
        return new ServiceInstance<>(
                "foo", "bar", "foo.com", 100, 200, null, 0, ServiceType.DYNAMIC,
                new UriSpec("{scheme}://{address}:{port}"), enabled);
    }
}
