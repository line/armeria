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

import java.util.Objects;

import javax.annotation.Nullable;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.logging.RequestLog;

/**
 * A container of a Thrift reply or exception object ({@link TBase} or {@link TApplicationException}) and
 * its header ({@link TMessage}). It is exported to {@link RequestLog#responseContent()} when a Thrift call
 * is processed.
 */
@JsonSerialize(using = ThriftJacksonSerializers.ThriftReplyJsonSerializer.class)
public final class ThriftReply extends ThriftMessage {

    @Nullable
    private final TBase<?, ?> result;
    @Nullable
    private final TApplicationException exception;

    /**
     * Creates a new instance that contains a Thrift {@link TMessageType#REPLY} message.
     */
    public ThriftReply(TMessage header, TBase<?, ?> result) {
        super(header);
        if (header.type != TMessageType.REPLY) {
            throw new IllegalArgumentException(
                    "header.type: " + typeStr(header.type) + " (expected: REPLY)");
        }

        this.result = requireNonNull(result, "result");
        exception = null;
    }

    /**
     * Creates a new instance that contains a Thrift {@link TMessageType#EXCEPTION} message.
     */
    public ThriftReply(TMessage header, TApplicationException exception) {
        super(header);
        if (header.type != TMessageType.EXCEPTION) {
            throw new IllegalArgumentException(
                    "header.type: " + typeStr(header.type) + " (expected: EXCEPTION)");
        }

        result = null;
        this.exception = requireNonNull(exception, "exception");
    }

    /**
     * Returns {@code true} if the type of this reply is {@link TMessageType#EXCEPTION}.
     */
    public boolean isException() {
        return exception != null;
    }

    /**
     * Returns the result of this reply.
     *
     * @throws IllegalStateException if the type of this reply is not {@link TMessageType#REPLY}
     */
    public TBase<?, ?> result() {
        if (isException()) {
            throw new IllegalStateException("not a reply but an exception");
        }
        return result;
    }

    /**
     * Returns the exception of this reply.
     *
     * @throws IllegalStateException if the type of this reply is not {@link TMessageType#EXCEPTION}
     */
    public TApplicationException exception() {
        if (!isException()) {
            throw new IllegalStateException("not an exception but a reply");
        }
        return exception;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof ThriftReply)) {
            return false;
        }

        final ThriftReply that = (ThriftReply) o;
        return super.equals(that) &&
               Objects.equals(result, that.result) &&
               Objects.equals(exception, that.exception);
    }

    @Override
    public int hashCode() {
        return (super.hashCode() * 31 + Objects.hashCode(result)) * 31 + Objects.hashCode(exception);
    }

    @Override
    public String toString() {
        final ToStringHelper helper = MoreObjects.toStringHelper(this)
                                                 .add("seqId", header().seqid)
                                                 .add("type", typeStr())
                                                 .add("name", header().name);
        if (result != null) {
            helper.add("result", result);
        } else {
            helper.add("exception", exception);
        }

        return helper.toString();
    }
}
