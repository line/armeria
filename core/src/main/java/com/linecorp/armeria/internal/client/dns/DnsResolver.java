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

package com.linecorp.armeria.internal.client.dns;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.DnsCache;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.Unwrappable;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.util.concurrent.EventExecutor;

interface DnsResolver extends Unwrappable, SafeCloseable {

    static DnsResolver of(DnsNameResolver delegate, DnsCache dnsCache, EventExecutor eventLoop,
                          List<String> searchDomains, int ndots) {
        final DelegatingDnsResolver defaultResolver = new DelegatingDnsResolver(delegate, eventLoop);

        final CachingDnsResolver cachingResolver = new CachingDnsResolver(defaultResolver, dnsCache);
        if (searchDomains.isEmpty()) {
            return cachingResolver;
        }

        return new SearchDomainDnsResolver(cachingResolver, searchDomains, ndots);
    }

    CompletableFuture<List<DnsRecord>> resolve(DnsQuestionContext ctx, DnsQuestion question);

    @Override
    default DnsResolver unwrap() {
        return this;
    }
}
