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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.resolver.dns.DnsNameResolverTimeoutException;
import io.netty.resolver.dns.DnsQueryLifecycleObserver;

/**
 * A {@link DnsQueryLifecycleObserver} that helps capture custom dns metrics.
 */
final class DefaultDnsQueryLifecycleObserver implements DnsQueryLifecycleObserver {
    private static final String NAME_TAG = "name";
    private static final String RESULT_TAG = "result";
    private static final String SERVERS_TAG = "servers";
    private static final String SERVER_TAG = "server";
    private static final String CODE_TAG = "code";
    private static final String CAUSE_TAG = "cause";
    private static final String CNAME_TAG = "cname";

    private static final Pattern NXDOMAIN_EXCEPTION = Pattern.compile("\\bNXDOMAIN\\b");
    private static final Pattern CNAME_EXCEPTION = Pattern.compile("\\bCNAME\\b/");
    private static final Pattern NO_MATCHING_EXCEPTION = Pattern.compile("\\bmatching\\b");
    private static final Pattern UNRECOGNIZED_TYPE_EXCEPTION = Pattern.compile("\\bunrecognized\b/");

    private final MeterRegistry meterRegistry;
    private final MeterIdPrefix meterIdPrefix;
    private final Tag nameTag;

    private enum DnsExceptionTypes {

        NX_DOMAIN,
        CNAME_NOT_FOUND,
        NO_MATCHING_RECORD,
        UNRECOGNIZED_TYPE,
        NAME_SERVERS_EXHAUSTED,
        OTHERS,
        SERVER_TIMEOUT,
        RESOLVER_TIMEOUT;

        final String lowerCasedName;

        DnsExceptionTypes() {
            lowerCasedName = Ascii.toLowerCase(name());
        }
    }

    /**
     * Accepts meterRegistry.
     * @param meterRegistry {@link MeterRegistry} MeterRegistry to capture metrics.
     * @param question {@link DnsQuestion} DnsQuestion.
     */
    DefaultDnsQueryLifecycleObserver(MeterRegistry meterRegistry,
                                     DnsQuestion question, MeterIdPrefix prefix) {
        this.meterRegistry = meterRegistry;
        meterIdPrefix = prefix;
        nameTag = Tag.of(NAME_TAG, question.name());
    }

    @Override
    public void queryWritten(InetSocketAddress dnsServerAddress, ChannelFuture future) {
        meterRegistry.counter(meterIdPrefix.name() + ".written",
                              Arrays.asList(nameTag,
                              Tag.of(SERVER_TAG, dnsServerAddress.getAddress().getHostAddress())))
                              .increment();
    }

    @Override
    public void queryCancelled(int queriesRemaining) {
        meterRegistry.counter(meterIdPrefix.name() + ".cancelled",
                              Tags.of(nameTag)).increment();
    }

    @Override
    public DnsQueryLifecycleObserver queryRedirected(List<InetSocketAddress> nameServers) {
        meterRegistry.counter(meterIdPrefix.name() + ".redirected",
                              Arrays.asList(nameTag, Tag.of(SERVERS_TAG,
                              nameServers.stream().map(addr -> addr.getAddress().getHostAddress())
                              .collect(Collectors.joining(","))))).increment();
        return this;
    }

    @Override
    public DnsQueryLifecycleObserver queryCNAMEd(DnsQuestion cnameQuestion) {
        meterRegistry.counter(meterIdPrefix.name() + ".cnamed",
                              Arrays.asList(nameTag,
                              Tag.of(CNAME_TAG, cnameQuestion.name()))).increment();
        return this;
    }

    @Override
    public DnsQueryLifecycleObserver queryNoAnswer(DnsResponseCode code) {
        meterRegistry.counter(meterIdPrefix.name() + ".noanswer",
                              Arrays.asList(nameTag,
                              Tag.of(CODE_TAG, String.valueOf(code.intValue())))).increment();
        return this;
    }

    @Override
    public void queryFailed(Throwable cause) {
        meterRegistry.counter(meterIdPrefix.name(),
                              Arrays.asList(nameTag, Tag.of(RESULT_TAG, "failure"),
                              Tag.of(CAUSE_TAG, determineDnsExceptionTag(cause).lowerCasedName))).increment();
    }

    @Override
    public void querySucceed() {
        meterRegistry.counter(meterIdPrefix.name(),
                              Arrays.asList(nameTag, Tag.of(RESULT_TAG, "success"),
                              Tag.of(CAUSE_TAG, "none"))).increment();
    }

    private static DnsExceptionTypes determineDnsExceptionTag(Throwable cause) {
        if (cause instanceof DnsTimeoutException) {
            return DnsExceptionTypes.SERVER_TIMEOUT;
        } else if (cause instanceof DnsNameResolverTimeoutException) {
            return DnsExceptionTypes.RESOLVER_TIMEOUT;
        }
        final String message = cause.getMessage();
        if (NXDOMAIN_EXCEPTION.matcher(message).find()) {
            return DnsExceptionTypes.NX_DOMAIN;
        }

        if (CNAME_EXCEPTION.matcher(message).find()) {
            return DnsExceptionTypes.CNAME_NOT_FOUND;
        }

        if (NO_MATCHING_EXCEPTION.matcher(message).find()) {
            return DnsExceptionTypes.NO_MATCHING_RECORD;
        }

        if (UNRECOGNIZED_TYPE_EXCEPTION.matcher(message).find()) {
            return DnsExceptionTypes.UNRECOGNIZED_TYPE;
        }

        return DnsExceptionTypes.OTHERS;
    }
}
