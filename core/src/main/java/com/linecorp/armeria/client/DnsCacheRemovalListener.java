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
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;

/**
 * A DNS cache listener that receives a notification when an entry is removed from a {@link DnsCache}.
 */
@UnstableApi
@FunctionalInterface
public interface DnsCacheRemovalListener {
    /**
     * Invoked when a removal occurred.
     *
     * @param question the DNS question.
     * @param records the result of a successful DNS resolution. {@code null} if failed.
     * @param cause the cause of a failed DNS resolution. {@code null} if succeeded.
     */
    void onRemoval(DnsQuestion question, @Nullable List<DnsRecord> records,
                   @Nullable UnknownHostException cause);
}
