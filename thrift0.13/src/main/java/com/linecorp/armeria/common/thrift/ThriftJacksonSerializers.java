/*
 * Copyright 2020 LINE Corporation
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

import java.io.IOException;
import java.io.Serializable;
import java.util.function.Consumer;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TMemoryBuffer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.Serializers;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;

final class ThriftJacksonSerializers extends Serializers.Base implements Serializable {

    private static final long serialVersionUID = -285900387635271875L;

    private final boolean useNamedEnums;

    ThriftJacksonSerializers(boolean useNamedEnums) {
        this.useNamedEnums = useNamedEnums;
    }

    @Override
    public JsonSerializer<?> findSerializer(SerializationConfig config, JavaType type,
                                            BeanDescription beanDesc) {

        final Class<?> rawType = type.getRawClass();
        if (TMessage.class.isAssignableFrom(rawType)) {
            return new TMessageJsonSerializer();
        }
        if (TBase.class.isAssignableFrom(rawType)) {
            return new TBaseJsonSerializer(useNamedEnums);
        }
        if (TApplicationException.class.isAssignableFrom(rawType)) {
            return new TApplicationExceptionJsonSerializer(useNamedEnums);
        }
        if (ThriftCall.class.isAssignableFrom(rawType)) {
            return new ThriftCallJsonSerializer(useNamedEnums);
        }
        if (ThriftReply.class.isAssignableFrom(rawType)) {
            return new ThriftReplyJsonSerializer(useNamedEnums);
        }
        return super.findSerializer(config, type, beanDesc);
    }

    static void serializeTMessage(TMessage value, JsonGenerator gen) throws IOException {
        gen.writeStartObject();

        gen.writeStringField("name", value.name);
        gen.writeNumberField("type", value.type);
        gen.writeNumberField("seqid", value.seqid);

        gen.writeEndObject();
    }

    // TODO(trustin): Merge serializeTBase() and serializeTApplicationException() once we drop support for 0.9.
    //                Since 0.10, both TBase and TApplicationException implement TSerializable.
    @SuppressWarnings("rawtypes")
    static void serializeTBase(@Nullable TBase value, JsonGenerator gen,
                               boolean useNamedEnums) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }

        gen.writeRawValue(serializeTBaseLike(protocol -> {
            try {
                value.write(protocol);
            } catch (TException ex) {
                throw new IllegalArgumentException(ex);
            }
        }, useNamedEnums));
    }

    static void serializeTApplicationException(@Nullable TApplicationException value, JsonGenerator gen,
                                               boolean useNamedEnums) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }

        gen.writeRawValue(serializeTBaseLike(protocol -> {
            try {
                value.write(protocol);
            } catch (TException ex) {
                throw new IllegalArgumentException(ex);
            }
        }, useNamedEnums));
    }

    private static String serializeTBaseLike(Consumer<TProtocol> writer, boolean useNamedEnums) {
        final TMemoryBuffer buffer;
        try {
            buffer = new TMemoryBuffer(1024);
        } catch (Exception ex) {
            // TTransportException is added as a checked exception in Thrift 0.14.0
            // However, a TTransportException should not be raised by the Armeria Thrift implementations.
            return Exceptions.throwUnsafely(ex);
        }

        final TProtocolFactory factory = useNamedEnums ? ThriftProtocolFactories.TEXT_NAMED_ENUM
                                                       : ThriftProtocolFactories.TEXT;
        final TProtocol protocol = factory.getProtocol(buffer);
        writer.accept(protocol);
        return new String(buffer.getArray(), 0, buffer.length());
    }

    /**
     * Jackson {@link JsonSerializer} for {@link ThriftCall}.
     */
    static final class ThriftCallJsonSerializer extends StdSerializer<ThriftCall> {
        private static final long serialVersionUID = -4873295256482417316L;

        private final boolean useNamedEnums;

        @SuppressWarnings("unused")
        ThriftCallJsonSerializer() {
            this(false);
        }

        ThriftCallJsonSerializer(boolean useNamedEnums) {
            super(ThriftCall.class);
            this.useNamedEnums = useNamedEnums;
        }

        @Override
        public void serialize(ThriftCall value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            gen.writeStartObject();
            gen.writeFieldName("header");
            serializeTMessage(value.header(), gen);
            gen.writeFieldName("args");
            serializeTBase(value.args(), gen, useNamedEnums);
            gen.writeEndObject();
        }
    }

    /**
     * Jackson {@link JsonSerializer} for {@link ThriftReply}.
     */
    static final class ThriftReplyJsonSerializer extends StdSerializer<ThriftReply> {
        private static final long serialVersionUID = -783551224966265113L;

        private final boolean useNamedEnums;

        ThriftReplyJsonSerializer() {
            this(false);
        }

        ThriftReplyJsonSerializer(boolean useNamedEnums) {
            super(ThriftReply.class);
            this.useNamedEnums = useNamedEnums;
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
            gen.writeFieldName("header");
            serializeTMessage(value.header(), gen);

            final TBase<?, ?> result;
            final TApplicationException exception;
            if (value.isException()) {
                result = null;
                exception = value.exception();
            } else {
                result = value.result();
                exception = null;
            }

            gen.writeFieldName("result");
            serializeTBase(result, gen, useNamedEnums);
            gen.writeFieldName("exception");
            serializeTApplicationException(exception, gen, useNamedEnums);
            gen.writeEndObject();
        }
    }

    /**
     * Jackson {@link JsonSerializer} for {@link TMessage}.
     */
    static final class TMessageJsonSerializer extends StdSerializer<TMessage> {
        private static final long serialVersionUID = 9105150745657053783L;

        TMessageJsonSerializer() {
            super(TMessage.class);
        }

        @Override
        public void serialize(TMessage value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            serializeTMessage(value, gen);
        }
    }

    /**
     * Jackson {@link JsonSerializer} for {@link TBase}.
     */
    @SuppressWarnings("rawtypes")
    private static final class TBaseJsonSerializer extends StdSerializer<TBase> {

        private static final long serialVersionUID = -7954242119098597530L;

        private final boolean useNamedEnums;

        TBaseJsonSerializer() {
            this(false);
        }

        TBaseJsonSerializer(boolean useNamedEnums) {
            super(TBase.class);
            this.useNamedEnums = useNamedEnums;
        }

        @Override
        public void serialize(TBase value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            serializeTBase(value, gen, useNamedEnums);
        }
    }

    /**
     * Jackson {@link JsonSerializer} for {@link TApplicationException}.
     */
    private static final class TApplicationExceptionJsonSerializer
            extends StdSerializer<TApplicationException> {
        private static final long serialVersionUID = -7552338111791933510L;

        private final boolean useNamedEnums;

        TApplicationExceptionJsonSerializer() {
            this(false);
        }

        TApplicationExceptionJsonSerializer(boolean useNamedEnums) {
            super(TApplicationException.class);
            this.useNamedEnums = useNamedEnums;
        }

        @Override
        public void serialize(TApplicationException value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            serializeTApplicationException(value, gen, useNamedEnums);
        }
    }
}
