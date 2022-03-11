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

import java.net.UnknownHostException;
import java.util.List;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;

/**
 * A DNS cache that does nothing. This class is only used for testing.
 */
public enum NoopDnsCache implements DnsCache {
    INSTANCE;

    @Override
    public void cache(DnsQuestion question, Iterable<? extends DnsRecord> records) {}

    @Override
    public void cache(DnsQuestion question, UnknownHostException cause) {}

    @Nullable
    @Override
    public List<DnsRecord> get(DnsQuestion question) throws UnknownHostException {
        return null;
    }

    @Override
    public void remove(DnsQuestion question) {}

    @Override
    public void removeAll() {}

    @Override
    public void removalListener(DnsCacheRemovalListener listener) {}
}
