/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.internal.client;

import static java.util.Objects.requireNonNull;

import java.net.IDN;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecordType;

/**
 * A {@link DnsQuestion} implementation which does not append a dot (.) to the name.
 */
public final class DnsQuestionWithoutTrailingDot implements DnsQuestion {

    private final String name;
    private final DnsRecordType type;

    public static DnsQuestionWithoutTrailingDot of(String name, DnsRecordType type) {
        return new DnsQuestionWithoutTrailingDot(name, type);
    }

    private DnsQuestionWithoutTrailingDot(String name, DnsRecordType type) {
        this.name = IDN.toASCII(requireNonNull(name, "name"));
        this.type = requireNonNull(type, "type");
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
        return CLASS_IN;
    }

    @Override
    public long timeToLive() {
        return 0;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DnsQuestionWithoutTrailingDot)) {
            return false;
        }
        final DnsQuestionWithoutTrailingDot that = (DnsQuestionWithoutTrailingDot) o;
        return type.equals(that.type) && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + type.hashCode();
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder(64);
        buf.append("DnsQuestion(")
           .append(name())
           .append(" IN ")
           .append(type().name())
           .append(')');
        return buf.toString();
    }
}
