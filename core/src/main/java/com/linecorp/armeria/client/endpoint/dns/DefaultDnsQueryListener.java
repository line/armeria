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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.dns.DnsRecord;

/**
 * A default implementation of {@link DnsQueryListener} interface.
 */
enum DefaultDnsQueryListener implements DnsQueryListener {

    INSTANCE;

    private static final Logger logger = LoggerFactory.getLogger(DefaultDnsQueryListener.class);

    @Override
    public void onSuccess(List<DnsRecord> oldRecords, List<DnsRecord> newRecords, String logPrefix) {}

    @Override
    public void onFailure(List<DnsRecord> oldRecords, Throwable cause, String logPrefix, long delayMillis,
                          int attemptsSoFar) {
        logger.warn("{} DNS query failed; retrying in {} ms (attempts so far: {}):",
                    logPrefix, delayMillis, attemptsSoFar, cause);
    }
}
