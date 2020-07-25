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

import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.resolver.dns.DnsQueryLifecycleObserver;

/**
 * A {@link DnsQueryLifecycleObserver} that helps capture custom dns metrics.
 */
final class DefaultDnsQueryLifecycleObserver implements DnsQueryLifecycleObserver {

    private final Counter success;
    private final Counter failure;
    private final Counter protocolType;
    private final Counter dnsErrorCode;
    private final Counter queryWritten;
    private final Counter queryType;

    /**
     * Accepts meterRegistry.
     * @param meterRegistry {@link MeterRegistry} MeterRegistry to capture metrics.
     * @param question {@link DnsQuestion} DnsQuestion.
     */
    DefaultDnsQueryLifecycleObserver(MeterRegistry meterRegistry, DnsQuestion question, MeterIdPrefix prefix) {
        success = meterRegistry.counter(prefix.name(),
                prefix.withTags("name", question.name(), "result", "success",
                        "question", question.name()).tags());
        failure = meterRegistry.counter(prefix.name(),
                prefix.withTags("name", question.name(), "result", "failure",
                        "question", question.name()).tags());
        dnsErrorCode = meterRegistry.counter(prefix.name(),
                prefix.withTags("name", question.name(), "dns", "errorcodes",
                        "question", question.name()).tags());
        queryWritten = meterRegistry.counter(prefix.name(),
                prefix.withTags("name", question.name(), "written", question.type().name()).tags());
        queryType = meterRegistry.counter(prefix.name(),
                prefix.withTags("name", question.name(),
                        "type", question.type().name()).tags());
        protocolType = meterRegistry.counter(prefix.name(),
                prefix.withTags("name", question.name(), "protocol", getProtocolType(question.type()),
                        "question", question.name()).tags());
        protocolType.increment();
    }

    private static String getProtocolType(DnsRecordType type) {
        if (DnsRecordType.IXFR.equals(type) ||
                DnsRecordType.AXFR.equals(type)) {
            return "tcp";
        }
        return "udp";
    }

    @Override
    public void queryWritten(InetSocketAddress dnsServerAddress, ChannelFuture future) {
        queryWritten.increment();
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
        dnsErrorCode.increment();
        return this;
    }

    @Override
    public void queryFailed(Throwable cause) {
        failure.increment();
    }

    @Override
    public void querySucceed() {
        success.increment();
    }
}
