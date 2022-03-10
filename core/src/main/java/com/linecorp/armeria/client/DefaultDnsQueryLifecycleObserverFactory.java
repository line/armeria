/*
 * Copyright 2022 LINE Corporation
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

import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.resolver.dns.DnsQueryLifecycleObserver;
import io.netty.resolver.dns.DnsQueryLifecycleObserverFactory;

/**
 * A {@link DnsQueryLifecycleObserverFactory} factory that helps create DnsQueryLifecycleObserver.
 */
final class DefaultDnsQueryLifecycleObserverFactory implements DnsQueryLifecycleObserverFactory {

    private final MeterRegistry meterRegistry;
    private final MeterIdPrefix meterIdPrefix;

    DefaultDnsQueryLifecycleObserverFactory(MeterRegistry meterRegistry,
                                            MeterIdPrefix meterIdPrefix) {
        this.meterRegistry = meterRegistry;
        this.meterIdPrefix = meterIdPrefix;
    }

    @Override
    public DnsQueryLifecycleObserver newDnsQueryLifecycleObserver(DnsQuestion question) {
        return new DefaultDnsQueryLifecycleObserver(meterRegistry, question, meterIdPrefix);
    }
}
