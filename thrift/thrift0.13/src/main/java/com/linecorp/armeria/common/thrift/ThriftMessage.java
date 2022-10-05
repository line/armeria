/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common.thrift;

import static java.util.Objects.requireNonNull;

import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A container of a Thrift message produced by Apache Thrift.
 */
public abstract class ThriftMessage {

    private final TMessage header;

    ThriftMessage(TMessage header) {
        this.header = requireNonNull(header, "header");
    }

    /**
     * Returns the header part of the message.
     */
    public final TMessage header() {
        return header;
    }

    @Override
    public int hashCode() {
        return header.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof ThriftMessage)) {
            return false;
        }

        return header.equals(((ThriftMessage) o).header);
    }

    final String typeStr() {
        return typeStr(header.type);
    }

    static String typeStr(byte type) {
        switch (type) {
            case TMessageType.CALL:
                return "CALL";
            case TMessageType.ONEWAY:
                return "ONEWAY";
            case TMessageType.REPLY:
                return "REPLY";
            case TMessageType.EXCEPTION:
                return "EXCEPTION";
            default:
                return "UNKNOWN(" + (type & 0xFF) + ')';
        }
    }
}
