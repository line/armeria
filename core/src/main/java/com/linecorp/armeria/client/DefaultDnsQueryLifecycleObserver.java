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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.resolver.dns.DnsQueryLifecycleObserver;

/**
 * A {@link DnsQueryLifecycleObserver} that helps capture custom dns metrics.
 */
final class DefaultDnsQueryLifecycleObserver implements DnsQueryLifecycleObserver {

    private final PrometheusMeterRegistry registry;
    private final MeterIdPrefix meterIdPrefix;
    private final DnsQuestion question;
    private static final String NAME_TAG = "name";
    private static final String RESULT_TAG = "result";
    private static final String SERVER_TAG = "server";
    private static final String CODE_TAG = "code";
    private static final String CAUSE_TAG = "cause";
    private static final String CNAME_TAG = "cname";

    /**
     * Accepts meterRegistry.
     * @param meterRegistry {@link PrometheusMeterRegistry} PrometheusMeterRegistry to capture metrics.
     * @param question {@link DnsQuestion} DnsQuestion.
     */
    DefaultDnsQueryLifecycleObserver(PrometheusMeterRegistry meterRegistry,
                                     DnsQuestion question, MeterIdPrefix prefix) {
        registry = meterRegistry;
        meterIdPrefix = prefix;
        this.question = question;
    }

    @Override
    public void queryWritten(InetSocketAddress dnsServerAddress, ChannelFuture future) {
        registry.counter(meterIdPrefix.name().concat(".written"),
                Arrays.asList(Tag.of(NAME_TAG, question.name()),
                        Tag.of(SERVER_TAG, dnsServerAddress.getAddress().toString()))).increment();
    }

    @Override
    public void queryCancelled(int queriesRemaining) {
        registry.counter(meterIdPrefix.name().concat(".cancelled"),
                NAME_TAG, question.name()).increment();
    }

    @Override
    public DnsQueryLifecycleObserver queryRedirected(List<InetSocketAddress> nameServers) {
        registry.counter(meterIdPrefix.name().concat(".redirected"),
                Arrays.asList(Tag.of(NAME_TAG, question.name()), Tag.of(SERVER_TAG,
                        nameServers.stream().map(addr -> addr.getAddress().toString())
                                .collect(Collectors.joining(","))))).increment();
        return this;
    }

    @Override
    public DnsQueryLifecycleObserver queryCNAMEd(DnsQuestion cnameQuestion) {
        registry.counter(meterIdPrefix.name().concat(".cnamed"),
                Arrays.asList(Tag.of(NAME_TAG, question.name()),
                        Tag.of(CNAME_TAG, cnameQuestion.name()))).increment();
        return this;
    }

    @Override
    public DnsQueryLifecycleObserver queryNoAnswer(DnsResponseCode code) {
        registry.counter(meterIdPrefix.name().concat(".noanswer"),
                Arrays.asList(Tag.of(NAME_TAG, question.name()),
                        Tag.of(CODE_TAG, code.toString()))).increment();
        return this;
    }

    @Override
    public void queryFailed(Throwable cause) {
        registry.counter(meterIdPrefix.name(),
                Arrays.asList(Tag.of(NAME_TAG, question.name()), Tag.of(RESULT_TAG, "failure"),
                        Tag.of(CAUSE_TAG, cause.getMessage()))).increment();
    }

    @Override
    public void querySucceed() {
        registry.counter(meterIdPrefix.name(),
                Arrays.asList(Tag.of(NAME_TAG, question.name()), Tag.of(RESULT_TAG, "success"),
                        Tag.of(CAUSE_TAG, ""))).increment();
    }
}
