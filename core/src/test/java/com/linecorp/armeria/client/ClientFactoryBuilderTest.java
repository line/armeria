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
package com.linecorp.armeria.client;

import static com.linecorp.armeria.client.ClientFactoryBuilder.MIN_PING_INTERVAL_MILLIS;
import static io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS;
import static io.netty.channel.ChannelOption.SO_LINGER;
import static io.netty.channel.epoll.EpollChannelOption.TCP_USER_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.internal.common.util.BouncyCastleKeyFactoryProvider;
import com.linecorp.armeria.internal.common.util.ChannelUtil;

import io.netty.channel.ChannelOption;
import io.netty.resolver.DefaultAddressResolverGroup;

class ClientFactoryBuilderTest {

    @Test
    void addressResolverGroupFactoryAndDomainNameResolverCustomizerAreMutuallyExclusive() {
        final ClientFactoryBuilder builder1 = ClientFactory.builder();
        builder1.addressResolverGroupFactory(eventLoopGroup -> null);
        assertThatThrownBy(() -> builder1.domainNameResolverCustomizer(b -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutually exclusive");

        final ClientFactoryBuilder builder2 = ClientFactory.builder();
        builder2.domainNameResolverCustomizer(b -> {});
        assertThatThrownBy(() -> builder2.addressResolverGroupFactory(eventLoopGroup -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void maxNumEventLoopsAndEventLoopSchedulerFactoryAreMutuallyExclusive() {
        final ClientFactoryBuilder builder1 = ClientFactory.builder();
        builder1.maxNumEventLoopsPerEndpoint(2);

        assertThatThrownBy(() -> builder1.eventLoopSchedulerFactory(
                eventLoopGroup -> mock(EventLoopScheduler.class))).isInstanceOf(IllegalStateException.class);

        final ClientFactoryBuilder builder2 = ClientFactory.builder();
        builder2.eventLoopSchedulerFactory(eventLoopGroup -> mock(EventLoopScheduler.class));

        assertThatThrownBy(() -> builder2.maxNumEventLoopsPerEndpoint(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void tlsNoVerifyAndTlsNoVerifyHostsAreMutuallyExclusive() {
        final ClientFactoryBuilder builder1 = ClientFactory.builder();
        builder1.tlsNoVerify();

        assertThatThrownBy(() -> builder1.tlsNoVerifyHosts("localhost"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutually exclusive");

        final ClientFactoryBuilder builder2 = ClientFactory.builder();
        builder2.tlsNoVerifyHosts("localhost");

        assertThatThrownBy(builder2::tlsNoVerify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void shouldInheritClientFactoryOptions() {
        try (ClientFactory factory1 = ClientFactory.builder()
                                                   .maxNumEventLoopsPerEndpoint(2)
                                                   .connectTimeoutMillis(5000)
                                                   .build();
             ClientFactory factory2 = ClientFactory.builder()
                                                   .options(factory1.options())
                                                   .idleTimeoutMillis(30000)
                                                   .build()) {

            final ClientFactoryOptions factory1Option = factory1.options();
            final ClientFactoryOptions factory2Option = factory2.options();
            for (ClientFactoryOptionValue<?> optionValue : factory2.options()) {
                final ClientFactoryOption<?> option = optionValue.option();
                if (option.compareTo(ClientFactoryOptions.IDLE_TIMEOUT_MILLIS) == 0) {
                    assertThat(factory1Option.get(option)).isNotEqualTo(factory2Option.get(option));
                } else {
                    assertThat(factory1Option.get(option)).isEqualTo(factory2Option.get(option));
                }
            }
        }
    }

    @Test
    void shouldPreserveChannelOptionInClientFactory() {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .options(ClientFactoryOptions.of())
                                                  .build()) {
            final Map<ChannelOption<?>, Object> channelOptions =
                    factory.options().get(ClientFactoryOptions.CHANNEL_OPTIONS);
            final int connectTimeoutMillis = (int) channelOptions.get(ChannelOption.CONNECT_TIMEOUT_MILLIS);
            assertThat(connectTimeoutMillis).isEqualTo(Flags.defaultConnectTimeoutMillis());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "com.linecorp.armeria.useJdkDnsResolver", matches = "true")
    void useDefaultAddressResolverGroup() {
        final DefaultClientFactory clientFactory = (DefaultClientFactory) ClientFactory.ofDefault();
        assertThat(clientFactory.addressResolverGroup()).isSameAs(DefaultAddressResolverGroup.INSTANCE);
    }

    @Test
    @DisabledIfSystemProperty(named = "com.linecorp.armeria.useJdkDnsResolver", matches = "true")
    void useRefreshingAddressResolverGroup() {
        final DefaultClientFactory clientFactory = (DefaultClientFactory) ClientFactory.ofDefault();
        assertThat(clientFactory.addressResolverGroup()).isInstanceOf(RefreshingAddressResolverGroup.class);
    }

    @ParameterizedTest
    @CsvSource({ "pkcs5.key", "pkcs8.key" })
    void shouldAllowPkcsPrivateKeys(String privateKeyPath) {
        final String resourceRoot =
                '/' + BouncyCastleKeyFactoryProvider.class.getPackage().getName().replace('.', '/') + '/';
        ClientFactory.builder().tlsCustomizer(sslCtxBuilder -> {
            sslCtxBuilder.keyManager(
                    getClass().getResourceAsStream(resourceRoot + "test.crt"),
                    getClass().getResourceAsStream(resourceRoot + privateKeyPath));
        }).build().close();
    }

    @Test
    void positivePingIntervalShouldBeGreaterThan1Second() {
        try (ClientFactory factory1 = ClientFactory.builder()
                                                   .idleTimeoutMillis(10000)
                                                   .pingIntervalMillis(0)
                                                   .build()) {
            assertThat(factory1.options().idleTimeoutMillis()).isEqualTo(10000);
            assertThat(factory1.options().pingIntervalMillis()).isEqualTo(0);

            assertThatThrownBy(() -> {
                ClientFactory.builder()
                             .idleTimeoutMillis(10000)
                             .pingIntervalMillis(MIN_PING_INTERVAL_MILLIS - 1)
                             .build();
            }).isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("(expected: >= " + MIN_PING_INTERVAL_MILLIS + " or == 0)");
        }

        try (ClientFactory factory2 = ClientFactory.builder()
                                                   .idleTimeoutMillis(10000)
                                                   .pingIntervalMillis(10000)
                                                   .build()) {
            assertThat(factory2.options().idleTimeoutMillis()).isEqualTo(10000);
            assertThat(factory2.options().pingIntervalMillis()).isEqualTo(0);
        }

        try (ClientFactory factory3 = ClientFactory.builder()
                                                   .idleTimeoutMillis(15000)
                                                   .pingIntervalMillis(MIN_PING_INTERVAL_MILLIS)
                                                   .build()) {
            assertThat(factory3.options().idleTimeoutMillis()).isEqualTo(15000);
            assertThat(factory3.options().pingIntervalMillis()).isEqualTo(MIN_PING_INTERVAL_MILLIS);
        }

        try (ClientFactory factory4 = ClientFactory.builder()
                                                   .idleTimeoutMillis(15000)
                                                   .pingIntervalMillis(14999)
                                                   .build()) {
            assertThat(factory4.options().idleTimeoutMillis()).isEqualTo(15000);
            assertThat(factory4.options().pingIntervalMillis()).isEqualTo(14999);
        }
    }

    @CsvSource({
            "0,     10000",
            "15000, 20000",
            "20000, 15000",
    })
    @ParameterizedTest
    void pingIntervalShouldBeLessThanIdleTimeout(long idleTimeoutMillis, long pingIntervalMillis) {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .idleTimeoutMillis(idleTimeoutMillis)
                                                  .pingIntervalMillis(pingIntervalMillis)
                                                  .build()) {
            assertThat(factory.options().idleTimeoutMillis()).isEqualTo(idleTimeoutMillis);
            if (idleTimeoutMillis > 0 && pingIntervalMillis >= idleTimeoutMillis) {
                assertThat(factory.options().pingIntervalMillis()).isEqualTo(0);
            } else {
                assertThat(factory.options().pingIntervalMillis()).isEqualTo(pingIntervalMillis);
            }
        }
    }

    @CsvSource({
            "9999,  0",
            "9999,  10000",
            "10000, 10000",
            "10000, 10001",
            "0,     10001",
    })
    @ParameterizedTest
    void clampedIdleTimeout(long idleTimeoutMillis, long maxConnectionAgeMillis) {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .idleTimeoutMillis(idleTimeoutMillis)
                                                  .maxConnectionAgeMillis(maxConnectionAgeMillis)
                                                  .build()) {
            assertThat(factory.options().maxConnectionAgeMillis()).isEqualTo(maxConnectionAgeMillis);

            if (maxConnectionAgeMillis == 0) {
                // Preserve the specified idleTimeoutMillis
                assertThat(factory.options().idleTimeoutMillis()).isEqualTo(idleTimeoutMillis);
            } else if (maxConnectionAgeMillis > 0) {
                assertThat(factory.options().idleTimeoutMillis())
                        .isEqualTo(Math.min(idleTimeoutMillis, maxConnectionAgeMillis));
            }
        }
    }

    @Test
    void clampedIdleTimeoutShouldAdjustPingTimeout() {
        final int idleTimeoutMillis = 3000;
        final int maxConnectionAgeMillis = 1000;
        final int pingIntervalMillis = 2000;
        try (ClientFactory factory = ClientFactory.builder()
                                                  .idleTimeoutMillis(idleTimeoutMillis)
                                                  .maxConnectionAgeMillis(maxConnectionAgeMillis)
                                                  .pingIntervalMillis(pingIntervalMillis)
                                                  .build()) {
            // idleTimeout should not be greater than maxConnectionAge.
            assertThat(factory.options().idleTimeoutMillis()).isEqualTo(maxConnectionAgeMillis);
            // pingInterval should be disabled because idleTimeout will work first.
            assertThat(factory.options().pingIntervalMillis()).isEqualTo(0);
        }
    }

    @Test
    void defaultTcpUserTimeoutSet() {
        assumeThat(Flags.transportType()).isEqualTo(TransportType.EPOLL);

        final int lingerMillis = 100;
        final int idleTimeoutMillis = 10_000;
        try (ClientFactory factory = ClientFactory.builder()
                                                  .idleTimeoutMillis(idleTimeoutMillis)
                                                  .channelOption(SO_LINGER, lingerMillis)
                                                  .build()) {
            assertThat(factory.options().channelOptions()).containsOnly(
                    entry(TCP_USER_TIMEOUT, idleTimeoutMillis + ChannelUtil.TCP_USER_TIMEOUT_BUFFER_MILLIS),
                    entry(SO_LINGER, lingerMillis),
                    entry(CONNECT_TIMEOUT_MILLIS, (int) Flags.defaultConnectTimeoutMillis()));
        }

        // user defined value is respected
        final int userDefinedValue = 3000;
        try (ClientFactory factory = ClientFactory.builder()
                                                  .idleTimeoutMillis(idleTimeoutMillis)
                                                  .channelOption(SO_LINGER, lingerMillis)
                                                  .channelOption(TCP_USER_TIMEOUT, userDefinedValue)
                                                  .build()) {
            assertThat(factory.options().channelOptions()).containsOnly(
                    entry(TCP_USER_TIMEOUT, userDefinedValue), entry(SO_LINGER, lingerMillis),
                    entry(CONNECT_TIMEOUT_MILLIS, (int) Flags.defaultConnectTimeoutMillis()));
        }
    }
}
