/*
 * Copyright 2015 LINE Corporation
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
// =================================================================================================
// Copyright 2011 Twitter, Inc.
// -------------------------------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this work except in compliance with the License.
// You may obtain a copy of the License in the LICENSE file, or at:
//
//  https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// =================================================================================================
package com.linecorp.armeria.common.thrift.text;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.thrift.protocol.TMessageType;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A type parsing helper, knows how to parse a given type either from a string
 * or from a JsonElement, and knows how to emit a given type to a JsonGenerator.
 *
 * <p>Clients should use the static members defined here for common types.
 * Should be implemented for each integral type we need to read/write.
 *
 * @author Alex Roetter
 *
 * @param <T> The type we are trying to read.
 */
abstract class TypedParser<T> {
    // Static methods clients can use.
    static final TypedParser<Boolean> BOOLEAN = new TypedParser<Boolean>() {

        @Override
        public Boolean readFromString(String s) {
            return Boolean.parseBoolean(s);
        }

        @Override
        public Boolean readFromJsonElement(JsonNode elem) {
            return elem.asBoolean();
        }

        @Override
        public void writeValue(JsonGenerator jw, Boolean val) throws IOException {
            jw.writeBoolean(val);
        }
    };

    static final TypedParser<Byte> BYTE = new TypedParser<Byte>() {

        @Override
        public Byte readFromString(String s) {
            return Byte.parseByte(s);
        }

        @Override
        public Byte readFromJsonElement(JsonNode elem) {
            return (byte) elem.asInt();
        }

        @Override
        public void writeValue(JsonGenerator jw, Byte val) throws IOException {
            jw.writeNumber(val);
        }
    };

    static final TypedParser<Short> SHORT = new TypedParser<Short>() {

        @Override
        public Short readFromString(String s) {
            return Short.parseShort(s);
        }

        @Override
        public Short readFromJsonElement(JsonNode elem) {
            return (short) elem.asInt();
        }

        @Override
        public void writeValue(JsonGenerator jw, Short val) throws IOException {
            jw.writeNumber(val);
        }
    };

    static final TypedParser<Integer> INTEGER = new TypedParser<Integer>() {

        @Override
        public Integer readFromString(String s) {
            return Integer.parseInt(s);
        }

        @Override
        public Integer readFromJsonElement(JsonNode elem) {
            return elem.asInt();
        }

        @Override
        public void writeValue(JsonGenerator jw, Integer val) throws IOException {
            jw.writeNumber(val);
        }
    };
    static final TypedParser<Long> LONG = new TypedParser<Long>() {

        @Override
        public Long readFromString(String s) {
            return Long.parseLong(s);
        }

        @Override
        public Long readFromJsonElement(JsonNode elem) {
            return elem.asLong();
        }

        @Override
        public void writeValue(JsonGenerator jw, Long val) throws IOException {
            jw.writeNumber(val);
        }
    };
    static final TypedParser<Double> DOUBLE = new TypedParser<Double>() {

        @Override
        public Double readFromString(String s) {
            return Double.parseDouble(s);
        }

        @Override
        public Double readFromJsonElement(JsonNode elem) {
            return elem.asDouble();
        }

        @Override
        public void writeValue(JsonGenerator jw, Double val) throws IOException {
            jw.writeNumber(val);
        }
    };
    static final TypedParser<String> STRING = new TypedParser<String>() {

        @Override
        public String readFromString(String s) {
            return s;
        }

        @Override
        public String readFromJsonElement(JsonNode elem) {
            return elem.asText();
        }

        @Override
        public void writeValue(JsonGenerator jw, String val) throws IOException {
            jw.writeString(val);
        }
    };
    static final TypedParser<ByteBuffer> BINARY = new TypedParser<ByteBuffer>() {

        @Override
        public ByteBuffer readFromString(String s) {
            return ByteBuffer.wrap(Base64Variants.getDefaultVariant().decode(s));
        }

        @Override
        public ByteBuffer readFromJsonElement(JsonNode elem) {
            try {
                return ByteBuffer.wrap(elem.binaryValue());
            } catch (IOException e) {
                throw new IllegalArgumentException("Error decoding binary value, is it valid base64?", e);
            }
        }

        @Override
        public void writeValue(JsonGenerator jw, ByteBuffer val) throws IOException {
            jw.writeBinary(val.array());
        }
    };

    static final TypedParser<Byte> TMESSAGE_TYPE = new TypedParser<Byte>() {
        @Override
        Byte readFromString(String s) {
            switch (s) {
            case "CALL":
                return TMessageType.CALL;
            case "REPLY":
                return TMessageType.REPLY;
            case "EXCEPTION":
                return TMessageType.EXCEPTION;
            case "ONEWAY":
                return TMessageType.ONEWAY;
            default:
                throw new IllegalArgumentException("Unsupported message type: " + s);
            }
        }

        @Override
        Byte readFromJsonElement(JsonNode elem) {
            return readFromString(elem.asText());
        }

        @Override
        void writeValue(JsonGenerator jw, Byte val) throws IOException {
            String serialized;
            switch (val.byteValue()) {
            case TMessageType.CALL:
                serialized = "CALL";
                break;
            case TMessageType.REPLY:
                serialized = "REPLY";
                break;
            case TMessageType.EXCEPTION:
                serialized = "EXCEPTION";
                break;
            case TMessageType.ONEWAY:
                serialized = "ONEWAY";
                break;
            default:
                throw new IllegalArgumentException("Unsupported message type: " + val);
            }
            jw.writeString(serialized);
        }
    };

    /**
     * Convert from a string to the given type.
     */
    abstract T readFromString(String s);

    /**
     * Read the given type from a JsonElement.
     */
    abstract T readFromJsonElement(JsonNode elem);

    /**
     * Write the given type out using a JsonGenerator.
     */
    abstract void writeValue(JsonGenerator jw, T val) throws IOException;
}
