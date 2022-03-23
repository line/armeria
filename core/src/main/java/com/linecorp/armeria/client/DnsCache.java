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

import static java.util.Objects.requireNonNull;

import java.net.UnknownHostException;
import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;

/**
 * A cache for DNS responses.
 */
@UnstableApi
public interface DnsCache {

    /**
     * Returns the default DNS cache.
     */
    static DnsCache ofDefault() {
        return DnsCacheBuilder.DEFAULT_CACHE;
    }

    /**
     * Returns a newly-created {@link DnsCache} using the specified {@code cacheSpec}.
     */
    static DnsCache of(String cacheSpec) {
        return builder().cacheSpec(cacheSpec).build();
    }

    /**
     * Returns a new {@link DnsCacheBuilder}.
     */
    static DnsCacheBuilder builder() {
        return new DnsCacheBuilder();
    }

    /**
     * Caches a successful resolution.
     *
     * @param question the DNS question.
     * @param records the DNS records associated to the given {@link DnsQuestion}.
     */
    default void cache(DnsQuestion question, DnsRecord... records) {
        requireNonNull(question, "question");
        requireNonNull(records, "record");
        cache(question, ImmutableList.copyOf(records));
    }

    /**
     * Caches a successful resolution.
     *
     * @param question the DNS question.
     * @param records the DNS records associated to the given {@link DnsQuestion}.
     */
    void cache(DnsQuestion question, Iterable<? extends DnsRecord> records);

    /**
     * Caches a failed resolution.
     *
     * @param question the DNS question.
     * @param cause the resolution failure.
     */
    void cache(DnsQuestion question, UnknownHostException cause);

    /**
     * Returns the {@link DnsRecord}s associated with the {@link DnsQuestion} in this cache.
     * {@code null} if this cache contains no resolution for the {@link DnsQuestion}.
     *
     * @throws UnknownHostException if the {@link DnsQuestion} previously failed with the
     *                              {@link UnknownHostException} and its negative TTL is valid.
     */
    @Nullable
    List<DnsRecord> get(DnsQuestion question) throws UnknownHostException;

    /**
     * Discards any cached value for the hostname.
     */
    void remove(DnsQuestion question);

    /**
     * Discards all entries in this cache.
     */
    void removeAll();

    /**
     * Adds a listener to this {@link DnsCache}. The {@link DnsCacheListener} is notified whenever an event
     * occurs.
     */
    void addListener(DnsCacheListener listener);
}
