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

import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.retry.Backoff;

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

        // IDN
        assertThat(new Builder("아르메리아").hostname()).isEqualTo("xn--2w2b2dxu436ada");
    }

    @Test
    void eventLoop() {
        assertThat(builder().eventLoop()).isNotNull();
        final EventLoop loop = new NioEventLoopGroup().next();
        assertThat(builder().eventLoop(loop).eventLoop()).isSameAs(loop);
        assertThatThrownBy(() -> builder().eventLoop(new DefaultEventLoop()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported");
    }

    @Test
    void ttl() {
        assertThat(builder().minTtl()).isOne();
        assertThat(builder().maxTtl()).isEqualTo(Integer.MAX_VALUE);
        final Builder builderWithCustomTtl = builder().ttl(10, 20);

        assertThat(builderWithCustomTtl.minTtl()).isEqualTo(10);
        assertThat(builderWithCustomTtl.maxTtl()).isEqualTo(20);
        assertThatThrownBy(() -> builder().ttl(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().ttl(20, 10)).isInstanceOf(IllegalArgumentException.class);

        final Builder builderWithSameCustomTtl = builder().ttl(1, 1);
        assertThat(builderWithSameCustomTtl.minTtl()).isOne();
        assertThat(builderWithSameCustomTtl.maxTtl()).isOne();
    }

    @Test
    void serverAddresses() {
        // Should be set by default.
        assertThat(builder().serverAddressStreamProvider()).isNotNull();

        // Should use the sequential stream when set by a user.
        final DnsServerAddressStreamProvider provider =
                builder().serverAddresses(new InetSocketAddress("1.1.1.1", 53),
                                          new InetSocketAddress("1.0.0.1", 53))
                         .serverAddressStreamProvider();

        final DnsServerAddressStream stream = provider.nameServerAddressStream("foo.com");
        assertThat(stream.size()).isEqualTo(2);
        assertThat(stream.next()).isEqualTo(new InetSocketAddress("1.1.1.1", 53));
        assertThat(stream.next()).isEqualTo(new InetSocketAddress("1.0.0.1", 53));
        assertThat(stream.next()).isEqualTo(new InetSocketAddress("1.1.1.1", 53));
        assertThat(stream.next()).isEqualTo(new InetSocketAddress("1.0.0.1", 53));
    }

    private static Builder builder() {
        return new Builder("foo.com");
    }

    private static final class Builder extends DnsEndpointGroupBuilder {
        Builder(String hostname) {
            super(hostname);
        }

        @Override
        public Builder eventLoop(EventLoop eventLoop) {
            return (Builder) super.eventLoop(eventLoop);
        }

        @Override
        public Builder ttl(int minTtl, int maxTtl) {
            return (Builder) super.ttl(minTtl, maxTtl);
        }

        @Override
        public Builder serverAddresses(InetSocketAddress... serverAddresses) {
            return (Builder) super.serverAddresses(serverAddresses);
        }

        @Override
        public Builder serverAddresses(Iterable<InetSocketAddress> serverAddresses) {
            return (Builder) super.serverAddresses(serverAddresses);
        }

        @Override
        public Builder backoff(Backoff backoff) {
            return (Builder) super.backoff(backoff);
        }

        @Override
        public Builder selectionStrategy(EndpointSelectionStrategy selectionStrategy) {
            return (Builder) super.selectionStrategy(selectionStrategy);
        }
    }
}
