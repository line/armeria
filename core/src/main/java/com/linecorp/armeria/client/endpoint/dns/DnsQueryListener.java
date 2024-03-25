/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.client.endpoint.dns;

import java.util.List;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.handler.codec.dns.DnsRecord;

/**
 *  Listens to the result of querying {@link DnsRecord}s.
 */
@UnstableApi
public interface DnsQueryListener {

    /**
     * Returns the default {@link DnsQueryListener} which logs {@link DnsRecord} query failures.
     */
    static DnsQueryListener of() {
        return DefaultDnsQueryListener.INSTANCE;
    }

    /**
     * Invoked when queries for {@link DnsRecord}s are successful.
     *
     * @param oldRecords old dns records which were to be updated. If empty, it indicates that
     *                   this querying is called after initialization or the old records had failed.
     * @param newRecords new dns records.
     * @param logPrefix comma-separated dns record name. (e.g., `foo.com, bar.com`)
     */
    void onSuccess(List<DnsRecord> oldRecords, List<DnsRecord> newRecords, String logPrefix);

    /**
     * Invoked when queries for {@link DnsRecord}s have failed.
     *
     * @param oldRecords old dns records which were to be updated. If empty, it indicates that
     *                   this querying is called after initialization.
     * @param cause the cause of the failure.
     * @param logPrefix comma-separated dns record name. (e.g., `foo.com, bar.com`)
     * @param delayMillis the interval of the next attempt.
     * @param attemptsSoFar the number of inquiries so far.
     */
    void onFailure(List<DnsRecord> oldRecords, Throwable cause, String logPrefix, long delayMillis,
                   int attemptsSoFar);
}
