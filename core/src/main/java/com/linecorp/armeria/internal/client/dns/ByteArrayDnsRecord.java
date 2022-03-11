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

import static java.util.Objects.requireNonNull;

import java.util.Arrays;

import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.util.internal.StringUtil;

/**
 * A {@code byte[]}-based {@link DnsRecord}.
 */
public final class ByteArrayDnsRecord implements DnsRecord {

    private static final byte[] EMPTY_BYTES = new byte[0];

    public static DnsRecord copyOf(DnsRecord dnsRecord) {
        requireNonNull(dnsRecord, "dnsRecord");
        if (dnsRecord instanceof ByteArrayDnsRecord) {
            return dnsRecord;
        }

        final byte[] content;
        if (dnsRecord instanceof ByteBufHolder) {
            final ByteBuf byteBuf = ((ByteBufHolder) dnsRecord).content();
            content = ByteBufUtil.getBytes(byteBuf);
            byteBuf.release();
        } else {
            content = EMPTY_BYTES;
        }
        return new ByteArrayDnsRecord(dnsRecord.name(), dnsRecord.type(), dnsRecord.dnsClass(),
                                      dnsRecord.timeToLive(), content);
    }

    private final String name;
    private final DnsRecordType type;
    private final int dnsClass;
    private final long timeToLive;
    private final byte[] content;
    private final int hashCode;

    ByteArrayDnsRecord(String name, DnsRecordType type, long timeToLive, byte[] content) {
        this(name, type, DnsRecord.CLASS_IN, timeToLive, content);
    }

    ByteArrayDnsRecord(String name, DnsRecordType type, int dnsClass, long timeToLive, byte[] content) {
        this.name = name;
        this.type = type;
        this.dnsClass = dnsClass;
        this.timeToLive = timeToLive;
        this.content = content;

        int hashCode = name.hashCode();
        hashCode = 31 * hashCode + type.hashCode();
        hashCode = 31 * hashCode + dnsClass;
        hashCode = 31 * hashCode + (int) timeToLive;
        this.hashCode = hashCode * 31 + Arrays.hashCode(content);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public DnsRecordType type() {
        return type;
    }

    @Override
    public int dnsClass() {
        return dnsClass;
    }

    @Override
    public long timeToLive() {
        return timeToLive;
    }

    public byte[] content() {
        return content;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ByteArrayDnsRecord)) {
            return false;
        }
        final ByteArrayDnsRecord that = (ByteArrayDnsRecord) o;
        return dnsClass == that.dnsClass && timeToLive == that.timeToLive &&
               name.equals(that.name) && type.equals(that.type) &&
               Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        try (TemporaryThreadLocals ttl = TemporaryThreadLocals.acquire()) {
            final StringBuilder builder = ttl.stringBuilder();
            builder.append(StringUtil.simpleClassName(this)).append('(');
            final DnsRecordType type = type();
            if (type != DnsRecordType.OPT) {
                builder.append(name().isEmpty() ? "<root>" : name())
                       .append(' ')
                       .append(timeToLive())
                       .append(' ');

                appendRecordClass(builder, dnsClass())
                        .append(' ')
                        .append(type.name());
            } else {
                builder.append("OPT flags:")
                       .append(timeToLive())
                       .append(" udp:")
                       .append(dnsClass());
            }

            builder.append(' ')
                   .append(content().length)
                   .append("B)");

            return builder.toString();
        }
    }

    private static StringBuilder appendRecordClass(StringBuilder buf, int dnsClass) {
        final String name;
        switch (dnsClass &= 0xFFFF) {
            case DnsRecord.CLASS_IN:
                name = "IN";
                break;
            case DnsRecord.CLASS_CSNET:
                name = "CSNET";
                break;
            case DnsRecord.CLASS_CHAOS:
                name = "CHAOS";
                break;
            case DnsRecord.CLASS_HESIOD:
                name = "HESIOD";
                break;
            case DnsRecord.CLASS_NONE:
                name = "NONE";
                break;
            case DnsRecord.CLASS_ANY:
                name = "ANY";
                break;
            default:
                name = null;
                break;
        }

        if (name != null) {
            buf.append(name);
        } else {
            buf.append("UNKNOWN(").append(dnsClass).append(')');
        }

        return buf;
    }
}
