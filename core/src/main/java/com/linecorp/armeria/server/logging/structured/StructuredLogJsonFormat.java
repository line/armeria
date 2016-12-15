/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.logging.structured;

import java.io.IOException;
import java.util.function.Consumer;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TMemoryBuffer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.common.thrift.text.TTextProtocol;

/**
 * A utility to provide JSON based service log serialization.
 * The JSON serialization provided by this class deal with some known classes in a special way.
 * For the representation classes of Apache Thrift RPC objects such as {@link TBase} and
 * {@link TApplicationException}, it uses {@link TTextProtocol} to serialize objects in a human-readable format.
 */
public final class StructuredLogJsonFormat {
    private StructuredLogJsonFormat() {}

    /**
     * Returns newly created {@link ObjectMapper} which is configured properly to serialize some knows classes
     * in a good way.
     */
    public static ObjectMapper newObjectMapper(SimpleModule... userModules) {
        ObjectMapper objectMapper = new ObjectMapper();

        SimpleModule module = new SimpleModule();
        module.addSerializer(TMessage.class, new TMessageSerializer());
        module.addSerializer(TBase.class, new TBaseSerializer());
        module.addSerializer(TApplicationException.class, new TAplicationExceptionSerializer());
        module.addSerializer(ThriftCall.class, new ApacheThriftCallSerializer());
        module.addSerializer(ThriftReply.class, new ApacheThriftReplySerializer());
        objectMapper.registerModule(module);

        for (SimpleModule userModule : userModules) {
            objectMapper.registerModule(userModule);
        }

        return objectMapper;
    }

    private static String writeThriftObjectAsTText(Consumer<TProtocol> writer) {
        TMemoryBuffer buffer = new TMemoryBuffer(1024);
        TProtocol protocol = new TTextProtocol.Factory().getProtocol(buffer);
        writer.accept(protocol);
        return new String(buffer.getArray(), 0, buffer.length());
    }

    private static class TMessageSerializer extends StdSerializer<TMessage> {
        protected TMessageSerializer() {
            super(TMessage.class);
        }

        @Override
        public void serialize(TMessage value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            gen.writeStartObject();

            gen.writeStringField("name", value.name);
            gen.writeNumberField("type", value.type);
            gen.writeNumberField("seqid", value.seqid);

            gen.writeEndObject();
        }
    }

    @SuppressWarnings("rawtypes")
    private static class TBaseSerializer extends StdSerializer<TBase> {
        protected TBaseSerializer() {
            super(TBase.class);
        }

        @Override
        public void serialize(TBase value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeRawValue(writeThriftObjectAsTText(protocol -> {
                try {
                    value.write(protocol);
                } catch (TException ex) {
                    throw new IllegalArgumentException(ex);
                }
            }));
        }
    }

    private static class TAplicationExceptionSerializer extends StdSerializer<TApplicationException> {
        protected TAplicationExceptionSerializer() {
            super(TApplicationException.class);
        }

        @Override
        public void serialize(TApplicationException value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            gen.writeRawValue(writeThriftObjectAsTText(protocol -> {
                try {
                    value.write(protocol);
                } catch (TException ex) {
                    throw new IllegalArgumentException(ex);
                }
            }));
        }
    }

    private static class ApacheThriftCallSerializer extends StdSerializer<ThriftCall> {
        protected ApacheThriftCallSerializer() {
            super(ThriftCall.class);
        }

        @Override
        public void serialize(ThriftCall value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            gen.writeStartObject();

            gen.writeObjectField("header", value.header());
            gen.writeObjectField("args", value.args());

            gen.writeEndObject();
        }
    }

    private static class ApacheThriftReplySerializer extends StdSerializer<ThriftReply> {
        protected ApacheThriftReplySerializer() {
            super(ThriftReply.class);
        }

        @Override
        public void serialize(ThriftReply value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            if (value == null) {
                // Oneway function doesn't provide reply
                gen.writeNull();
                return;
            }

            gen.writeStartObject();

            gen.writeObjectField("header", value.header());

            final TBase<?, ?> result;
            final TApplicationException exception;
            if (value.isException()) {
                result = null;
                exception = value.exception();
            } else {
                result = value.result();
                exception = null;
            }

            gen.writeObjectField("result", result);
            gen.writeObjectField("exception", exception);

            gen.writeEndObject();
        }
    }
}
