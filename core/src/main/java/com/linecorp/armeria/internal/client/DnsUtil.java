/*
 *  Copyright 2019 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.internal.client;

import org.slf4j.Logger;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;

public final class DnsUtil {

    @Nullable
    public static byte[] extractAddressBytes(DnsRecord record, Logger logger, String logPrefix) {
        final DnsRecordType type = record.type();
        final ByteBuf content = ((ByteBufHolder) record).content();
        final int contentLen = content.readableBytes();

        // Skip invalid records.
        if (type == DnsRecordType.A) {
            if (contentLen != 4) {
                warnInvalidRecord(logger, logPrefix, type, content);
                return null;
            }
        } else if (type == DnsRecordType.AAAA) {
            if (contentLen != 16) {
                warnInvalidRecord(logger, logPrefix, type, content);
                return null;
            }
        } else {
            return null;
        }

        final byte[] addrBytes = new byte[contentLen];
        content.getBytes(content.readerIndex(), addrBytes);
        return addrBytes;
    }

    /**
     * Logs a warning message about an invalid record.
     */
    public static void warnInvalidRecord(Logger logger, String logPrefix, DnsRecordType type, ByteBuf content) {
        if (logger.isWarnEnabled()) {
            final String dump = ByteBufUtil.hexDump(content);
            logger.warn("{} Skipping invalid {} record: {}",
                        logPrefix, type.name(), dump.isEmpty() ? "<empty>" : dump);
        }
    }

    private DnsUtil() {}
}
