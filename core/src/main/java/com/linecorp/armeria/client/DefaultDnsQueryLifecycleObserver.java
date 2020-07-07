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
package com.linecorp.armeria.client;

import java.net.InetSocketAddress;
import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.resolver.dns.DnsQueryLifecycleObserver;

/**
 * A {@link DnsQueryLifecycleObserver} that helps capture custom dns metrics.
 */
public class DefaultDnsQueryLifecycleObserver implements DnsQueryLifecycleObserver {

    private final MeterRegistry meterRegistry;
    private final DnsQuestion dnsQuestion;

    /**
     * Accepts meterRegistry.
     * @param meterRegistry {@link MeterRegistry} MeterRegistry to capture metrics.
     * @param question {@link DnsQuestion} DnsQuestion.
     */
    public DefaultDnsQueryLifecycleObserver(MeterRegistry meterRegistry, DnsQuestion question) {
        this.dnsQuestion = question;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void queryWritten(InetSocketAddress dnsServerAddress, ChannelFuture future) {
        meterRegistry.counter("dns.query.count", dnsQuestion.name(), dnsQuestion.type().name()).increment();
    }

    @Override
    public void queryCancelled(int queriesRemaining) {
    }

    @Override
    public DnsQueryLifecycleObserver queryRedirected(List<InetSocketAddress> nameServers) {
        return null;
    }

    @Override
    public DnsQueryLifecycleObserver queryCNAMEd(DnsQuestion cnameQuestion) {
        return null;
    }

    @Override
    public DnsQueryLifecycleObserver queryNoAnswer(DnsResponseCode code) {
        return null;
    }

    @Override
    public void queryFailed(Throwable cause) {
        meterRegistry.counter("dns.query.failed").increment();
    }

    @Override
    public void querySucceed() {
        meterRegistry.counter("dns.query.succeeded").increment();
    }
}
