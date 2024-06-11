/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.client.endpoint.dns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.resolver.dns.DnsServerAddressStream;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;

class DnsEndpointGroupBuilderTest {

    @Test
    void hostname() {
        assertThat(new Builder("my-host.com").hostname()).isEqualTo("my-host.com");
        assertThat(new Builder("MY-HOST.COM").hostname()).isEqualTo("my-host.com");

        // Trailing dot
        assertThat(new Builder("my-host.com.").hostname()).isEqualTo("my-host.com.");
        assertThat(new Builder("MY-HOST.COM.").hostname()).isEqualTo("my-host.com.");

        // IDN
        assertThat(new Builder("아르메리아").hostname()).isEqualTo("xn--2w2b2dxu436ada");
    }

    @Test
    void eventLoop() {
        assertThat(builder().getOrAcquireEventLoop()).isNotNull();
        final EventLoop loop = new NioEventLoopGroup().next();
        assertThat(builder().eventLoop(loop).getOrAcquireEventLoop()).isSameAs(loop);
        assertThatThrownBy(() -> builder().eventLoop(new DefaultEventLoop()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported");
    }

    @Test
    void ttl() {
        assertThat(builder().minTtl0()).isOne();
        assertThat(builder().maxTtl0()).isEqualTo(Integer.MAX_VALUE);
        final Builder builderWithCustomTtl = builder().ttl(10, 20);

        assertThat(builderWithCustomTtl.minTtl0()).isEqualTo(10);
        assertThat(builderWithCustomTtl.maxTtl0()).isEqualTo(20);
        assertThatThrownBy(() -> builder().ttl(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().ttl(20, 10)).isInstanceOf(IllegalArgumentException.class);

        final Builder builderWithSameCustomTtl = builder().ttl(1, 1);
        assertThat(builderWithSameCustomTtl.minTtl0()).isOne();
        assertThat(builderWithSameCustomTtl.maxTtl0()).isOne();
    }

    @Test
    void serverAddresses() {
        // Should be set by default.
        assertThat(builder().serverAddressStreamProvider0()).isNotNull();

        // Should use the sequential stream when set by a user.
        final DnsServerAddressStreamProvider provider =
                builder().serverAddresses(new InetSocketAddress("1.1.1.1", 53),
                                          new InetSocketAddress("1.0.0.1", 53))
                         .serverAddressStreamProvider0();

        final DnsServerAddressStream stream = provider.nameServerAddressStream("foo.com");
        assertThat(stream.size()).isEqualTo(2);
        assertThat(stream.next()).isEqualTo(new InetSocketAddress("1.1.1.1", 53));
        assertThat(stream.next()).isEqualTo(new InetSocketAddress("1.0.0.1", 53));
        assertThat(stream.next()).isEqualTo(new InetSocketAddress("1.1.1.1", 53));
        assertThat(stream.next()).isEqualTo(new InetSocketAddress("1.0.0.1", 53));
    }

    @Test
    void allowEmptyEndpoints() {
        final DnsEndpointGroupBuilder<Builder> builder0 = new Builder("foo.com").allowEmptyEndpoints(false);
        assertThat(builder0.shouldAllowEmptyEndpoints()).isFalse();

        final DnsEndpointGroupBuilder<Builder> builder1 = new Builder("foo.com").allowEmptyEndpoints(true);
        assertThat(builder1.shouldAllowEmptyEndpoints()).isTrue();
    }

    private static Builder builder() {
        return new Builder("foo.com");
    }

    private static final class Builder extends DnsEndpointGroupBuilder<Builder> {
        Builder(String hostname) {
            super(hostname);
        }

        int minTtl0() {
            return minTtl();
        }

        int maxTtl0() {
            return maxTtl();
        }

        DnsServerAddressStreamProvider serverAddressStreamProvider0() {
            return serverAddressStreamProvider();
        }
    }
}
