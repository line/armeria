/*
 * Copyright 2021 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.netty.resolver.dns.LoggingDnsQueryLifeCycleObserverFactory;

class DnsResolverGroupBuilderTest {

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void disableDnsQueryLifecycleObserverFactory() {
        assertThatThrownBy(() -> {
            new DnsResolverGroupBuilder()
                    .disableDnsQueryLifecycleObserverFactory()
                    .dnsQueryLifecycleObserverFactory(new LoggingDnsQueryLifeCycleObserverFactory())
                    .build(eventLoop.get());
        }).isInstanceOf(IllegalStateException.class)
          .hasMessage("dnsQueryLifecycleObserverFactory has been disabled.");

        assertThatThrownBy(() -> {
            new DnsResolverGroupBuilder()
                    .dnsQueryLifecycleObserverFactory(new LoggingDnsQueryLifeCycleObserverFactory())
                    .disableDnsQueryLifecycleObserverFactory()
                    .build(eventLoop.get());
        }).isInstanceOf(IllegalStateException.class)
          .hasMessage("dnsQueryLifecycleObserverFactory has been set already.");
    }
}
