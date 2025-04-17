/*
 * Copyright 2025 LINE Corporation
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
/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.dns.DnsOpCode;
import io.netty.handler.codec.dns.DnsQuery;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordDecoder;
import io.netty.handler.codec.dns.DnsSection;

/**
 * Provides some utility methods for DNS message implementations.
 */
final class DnsMessageUtil {

    // Forked from https://github.com/netty/netty/blob/d639d876aa577eea21acebaa970c8cc60644a851/codec-dns/src/main/java/io/netty/handler/codec/dns/DnsMessageUtil.java#L28
    // TODO(ikhoon): Remove this class once https://github.com/netty/netty/pull/15033 is merged.

    static DnsQuery decodeDnsQuery(DnsRecordDecoder decoder, ByteBuf buf, DnsQueryFactory supplier)
            throws Exception {
        final DnsQuery query = newQuery(buf, supplier);
        boolean success = false;
        try {
            final int questionCount = buf.readUnsignedShort();
            final int answerCount = buf.readUnsignedShort();
            final int authorityRecordCount = buf.readUnsignedShort();
            final int additionalRecordCount = buf.readUnsignedShort();
            decodeQuestions(decoder, query, buf, questionCount);
            decodeRecords(decoder, query, DnsSection.ANSWER, buf, answerCount);
            decodeRecords(decoder, query, DnsSection.AUTHORITY, buf, authorityRecordCount);
            decodeRecords(decoder, query, DnsSection.ADDITIONAL, buf, additionalRecordCount);
            success = true;
            return query;
        } finally {
            if (!success) {
                query.release();
            }
        }
    }

    private static DnsQuery newQuery(ByteBuf buf, DnsQueryFactory supplier) {
        final int id = buf.readUnsignedShort();
        final int flags = buf.readUnsignedShort();
        if (flags >> 15 == 1) {
            throw new CorruptedFrameException("not a query");
        }

        final DnsQuery query = supplier.newQuery(id, DnsOpCode.valueOf((byte) (flags >> 11 & 0xf)));
        query.setRecursionDesired((flags >> 8 & 1) == 1);
        query.setZ(flags >> 4 & 0x7);
        return query;
    }

    private static void decodeQuestions(DnsRecordDecoder decoder,
                                        DnsQuery query, ByteBuf buf, int questionCount) throws Exception {
        for (int i = questionCount; i > 0; --i) {
            query.addRecord(DnsSection.QUESTION, decoder.decodeQuestion(buf));
        }
    }

    private static void decodeRecords(DnsRecordDecoder decoder, DnsQuery query, DnsSection section,
                                      ByteBuf buf, int count) throws Exception {
        for (int i = count; i > 0; --i) {
            final DnsRecord r = decoder.decodeRecord(buf);
            if (r == null) {
                break;
            }
            query.addRecord(section, r);
        }
    }

    interface DnsQueryFactory {
        DnsQuery newQuery(int id, DnsOpCode dnsOpCode);
    }

    private DnsMessageUtil() {
    }
}
