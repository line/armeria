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

import javax.annotation.Nullable;

import org.apache.thrift.TBase;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.logging.RequestLog;

/**
 * A container of a Thrift one-way or two-way call object ({@link TBase}) and its header ({@link TMessage}).
 * It is exported to {@link RequestLog#requestContent()} when a Thrift call is processed.
 */
@JsonSerialize(using = ThriftJacksonSerializers.ThriftCallJsonSerializer.class)
public final class ThriftCall extends ThriftMessage {

    private final TBase<?, ?> args;

    /**
     * Creates a new instance that contains a Thrift {@link TMessageType#CALL} or {@link TMessageType#ONEWAY}
     * message.
     */
    public ThriftCall(TMessage header, TBase<?, ?> args) {
        super(header);
        if (header.type != TMessageType.CALL && header.type != TMessageType.ONEWAY) {
            throw new IllegalArgumentException(
                    "header.type: " + typeStr(header.type) + " (expected: CALL or ONEWAY)");
        }

        this.args = requireNonNull(args, "args");
    }

    /**
     * Returns the arguments of this call.
     */
    public TBase<?, ?> args() {
        return args;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof ThriftCall)) {
            return false;
        }

        return super.equals(o) && args.equals(((ThriftCall) o).args);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + args.hashCode();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("seqId", header().seqid)
                          .add("type", typeStr())
                          .add("name", header().name)
                          .add("args", args).toString();
    }
}
