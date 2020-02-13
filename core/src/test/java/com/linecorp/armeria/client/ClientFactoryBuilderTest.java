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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.internal.common.util.BouncyCastleKeyFactoryProvider;

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

    @SuppressWarnings("unchecked")
    @Test
    void shouldInheritClientFactoryOptions() {
        final ClientFactory factory1 = ClientFactory.builder()
                                                    .maxNumEventLoopsPerEndpoint(2)
                                                    .connectTimeoutMillis(5000)
                                                    .build();

        final ClientFactory factory2 = ClientFactory.builder()
                                                    .options(factory1.options())
                                                    .idleTimeoutMillis(30000)
                                                    .build();

        assertThat(factory2.options().asMap()).allSatisfy((opt, optVal) -> {
            if (opt.compareTo(ClientFactoryOption.IDLE_TIMEOUT_MILLIS) == 0) {
                assertThat(optVal.value()).isNotEqualTo(factory1.options().asMap().get(opt).value());
            } else {
                assertThat(optVal.value()).isEqualTo(factory1.options().asMap().get(opt).value());
            }
        });
    }

    @Test
    void shouldPreserveChannelOptionInClientFactory() {
        final ClientFactory factory = ClientFactory.builder()
                .options(ClientFactoryOptions.of())
                .build();
        final Map<ChannelOption<?>, Object> channelOptions =
                factory.options().get(ClientFactoryOption.CHANNEL_OPTIONS);
        final int connectTimeoutMillis = (int) channelOptions.get(ChannelOption.CONNECT_TIMEOUT_MILLIS);
        assertThat(connectTimeoutMillis).isEqualTo(Flags.defaultConnectTimeoutMillis());
    }

    @Test
    @EnabledIfSystemProperty(named = "com.linecorp.armeria.useJdkDnsResolver", matches = "true")
    void useDefaultAddressResolverGroup() {
        final DefaultClientFactory clientFactory = (DefaultClientFactory) ClientFactory.ofDefault();
        assertThat(clientFactory.addressResolverGroup()).isSameAs(DefaultAddressResolverGroup.INSTANCE);
    }

    @Test
    @DisabledIfSystemProperty(named = "com.linecorp.armeria.useJdkDnsResolver",  matches = "true")
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
        }).build();
    }
}
