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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

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

        assertThrows(IllegalStateException.class,
                     () -> builder1.eventLoopSchedulerFactory(
                             eventLoopGroup -> mock(EventLoopScheduler.class)));

        final ClientFactoryBuilder builder2 = ClientFactory.builder();
        builder2.eventLoopSchedulerFactory(eventLoopGroup -> mock(EventLoopScheduler.class));

        final IllegalStateException cause = assertThrows(IllegalStateException.class,
                                                         () -> builder2.maxNumEventLoopsPerEndpoint(2));
        assertThat(cause).hasMessageContaining("mutually exclusive");
    }
}
