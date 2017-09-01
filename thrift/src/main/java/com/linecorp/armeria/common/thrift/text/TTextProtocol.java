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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Stack;

import org.apache.thrift.TBase;
import org.apache.thrift.TEnum;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TList;
import org.apache.thrift.protocol.TMap;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.protocol.TSet;
import org.apache.thrift.protocol.TStruct;
import org.apache.thrift.protocol.TType;
import org.apache.thrift.scheme.IScheme;
import org.apache.thrift.scheme.StandardScheme;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A simple text format for serializing/deserializing thrift
 * messages. This format is inefficient in space.
 *
 * <p>For an example, see:
 * tests/resources/com/twitter/common/thrift/text/TTextProtocol_TestData.txt
 *
 * <p>which is a text encoding of the thrift message defined in:
 *
 * <p>src/main/thrift/com/twitter/common/thrift/text/TTextProtocolTest.thrift
 *
 * <p>Whitespace (including newlines) is not significant.
 *
 * <p>No comments are allowed in the json.
 *
 * <p>Messages must be formatted as a JSON object with a field 'method' containing
 * the message name, 'type' containing the message type as an uppercase string
 * corresponding to {@link TMessageType}, 'args' containing a JSON object with
 * the actual arguments, and an optional 'seqid' field containing the sequence
 * id. If 'seqid' is not provided, it will be treated as 0. 'args' should use
 * the argument names as defined in the service definition.
 *
 * <p>Example:{@code
 *
 * {
 *     "method": "GetItem",
 *     "type": "CALL",
 *     "args": {
 *         "id": 1,
 *         "fetchAll": true
 *     },
 *     "seqid": 100
 * }
 *
 * }
 *
 * <p>TODO(Alex Roetter): write a wrapper that allows us to read in a file
 * of many structs (perhaps stored in a JsonArray), passing each struct to
 * this class for parsing.
 *
 * <p>See thrift's @see org.apache.thrift.protocol.TJSONProtocol
 * for another example an implementation of the @see TProtocol
 * interface. This class is based on that.
 *
 * <p>TODO(Alex Roetter): Also add a new TEXT_PROTOCOL field to ThriftCodec
 *
 * <p>TODO: Support map enum keys specified as strings.
 *
 * <p>TODO: Support string values for enums that have been typedef'd.
 */
public class TTextProtocol extends TProtocol {

    private static final String SEQUENCE_AS_KEY_ILLEGAL =
            "Can't have a sequence (list or set) as a key in a map!";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);

    private static final TStruct ANONYMOUS_STRUCT = new TStruct();

    // how many bytes to read at once
    private static final int READ_BUFFER_SIZE = 1024;

    private static final byte UNUSED_TYPE = TType.STOP;
    private final Stack<WriterByteArrayOutputStream> writers;
    private final Stack<BaseContext> contextStack;
    private final Stack<Class<?>> currentFieldClass;
    private JsonNode root;

    /**
     * Create a parser which can read from trans, and create the output writer
     * that can write to a TTransport.
     */
    public TTextProtocol(TTransport trans) {
        super(trans);

        writers = new Stack<>();
        contextStack = new Stack<>();
        currentFieldClass = new Stack<>();
        reset();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Class<? extends IScheme> getScheme() {
        return StandardScheme.class;
    }

    @Override
    public final void reset() {
        root = null;

        writers.clear();
        pushWriter(new TTransportOutputStream());

        contextStack.clear();
        contextStack.push(new BaseContext());
        currentFieldClass.clear();
    }

    /**
     * I believe these two messages are called for a thrift service
     * interface. We don't plan on storing any text objects of that
     * type on disk.
     */
    @Override
    public void writeMessageBegin(TMessage message) throws TException {
        try {
            getCurrentWriter().writeStartObject();
            getCurrentWriter().writeFieldName("method");
            getCurrentWriter().writeString(message.name);
            getCurrentWriter().writeFieldName("type");
            TypedParser.TMESSAGE_TYPE.writeValue(getCurrentWriter(), message.type);
            getCurrentWriter().writeFieldName("seqid");
            getCurrentWriter().writeNumber(message.seqid);
            getCurrentWriter().writeFieldName("args");
        } catch (IOException e) {
            throw new TTransportException(e);
        }
    }

    @Override
    public void writeMessageEnd() throws TException {
        try {
            getCurrentWriter().writeEndObject();
            getCurrentWriter().flush();
        } catch (IOException e) {
            throw new TTransportException(e);
        }
    }

    @Override
    public void writeStructBegin(TStruct struct) throws TException {
        writeJsonObjectBegin(new StructContext(null));
    }

    @Override
    public void writeStructEnd() throws TException {
        writeJsonObjectEnd();
    }

    @Override
    public void writeFieldBegin(TField field) throws TException {
        try {
            getCurrentWriter().writeFieldName(field.name);
        } catch (IOException ex) {
            throw new TException(ex);
        }
    }

    @Override
    public void writeFieldEnd() throws TException {
    }

    @Override
    public void writeFieldStop() throws TException {
    }

    @Override
    public void writeMapBegin(TMap map) throws TException {
        writeJsonObjectBegin(new MapContext(null));
    }

    @Override
    public void writeMapEnd() throws TException {
        writeJsonObjectEnd();
    }

    /**
     * Helper to write out the beginning of a Thrift type (either struct or map),
     * both of which are written as JsonObjects.
     */
    private void writeJsonObjectBegin(BaseContext context) throws TException {
        getCurrentContext().write();
        if (getCurrentContext().isMapKey()) {
            pushWriter(new ByteArrayOutputStream());
        }
        pushContext(context);
        try {
            getCurrentWriter().writeStartObject();
        } catch (IOException ex) {
            throw new TException(ex);
        }
    }

    /**
     * Helper to write out the end of a Thrift type (either struct or map),
     * both of which are written as JsonObjects.
     */
    private void writeJsonObjectEnd() throws TException {
        try {
            getCurrentWriter().writeEndObject();
            popContext();
            if (getCurrentContext().isMapKey()) {
                String writerString = getWriterString();
                popWriter();
                getCurrentWriter().writeFieldName(writerString);
            }

            // flush at the end of the final struct.
            if (1 == contextStack.size()) {
                getCurrentWriter().flush();
            }
        } catch (IOException ex) {
            throw new TException(ex);
        }
    }

    @Override
    public void writeListBegin(TList list) throws TException {
        writeSequenceBegin(list.size);
    }

    @Override
    public void writeListEnd() throws TException {
        writeSequenceEnd();
    }

    @Override
    public void writeSetBegin(TSet set) throws TException {
        writeSequenceBegin(set.size);
    }

    @Override
    public void writeSetEnd() throws TException {
        writeListEnd();
    }

    /**
     * Helper shared by write{List/Set}Begin.
     */
    private void writeSequenceBegin(int size) throws TException {
        getCurrentContext().write();
        if (getCurrentContext().isMapKey()) {
            throw new TException(SEQUENCE_AS_KEY_ILLEGAL);
        }
        pushContext(new SequenceContext(null));

        try {
            getCurrentWriter().writeStartArray();
        } catch (IOException ex) {
            throw new TTransportException(ex);
        }
    }

    /**
     * Helper shared by write{List/Set}End.
     */
    private void writeSequenceEnd() throws TException {
        try {
            getCurrentWriter().writeEndArray();
        } catch (IOException ex) {
            throw new TTransportException(ex);
        }
        popContext();
    }

    @Override
    public void writeBool(boolean b) throws TException {
        writeNameOrValue(TypedParser.BOOLEAN, b);
    }

    @Override
    public void writeByte(byte b) throws TException {
        writeNameOrValue(TypedParser.BYTE, b);
    }

    @Override
    public void writeI16(short i16) throws TException {
        writeNameOrValue(TypedParser.SHORT, i16);
    }

    @Override
    public void writeI32(int i32) throws TException {
        writeNameOrValue(TypedParser.INTEGER, i32);
    }

    @Override
    public void writeI64(long i64) throws TException {
        writeNameOrValue(TypedParser.LONG, i64);
    }

    @Override
    public void writeDouble(double dub) throws TException {
        writeNameOrValue(TypedParser.DOUBLE, dub);
    }

    @Override
    public void writeString(String str) throws TException {
        writeNameOrValue(TypedParser.STRING, str);
    }

    @Override
    public void writeBinary(ByteBuffer buf) throws TException {
        writeNameOrValue(TypedParser.BINARY, buf);
    }

    /**
     * Write out the given value, either as a JSON name (meaning it's
     * escaped by quotes), or a value. The TypedParser knows how to
     * handle the writing.
     */
    private <T> void writeNameOrValue(TypedParser<T> helper, T val)
            throws TException {
        getCurrentContext().write();
        try {
            if (getCurrentContext().isMapKey()) {
                getCurrentWriter().writeFieldName(val.toString());
            } else {
                helper.writeValue(getCurrentWriter(), val);
            }
        } catch (IOException ex) {
            throw new TException(ex);
        }
    }

    /////////////////////////////////////////
    // Read methods
    /////////////////////////////////////////
    @Override
    public TMessage readMessageBegin() throws TException {
        root = null;
        try {
            readRoot();
        } catch (IOException e) {
            throw new TException("Could not parse input, is it valid json?", e);
        }
        if (!root.isObject()) {
            throw new TException("The top level of the input must be a json object with method and args!");
        }

        if (!root.has("method")) {
            throw new TException("Object must have field 'method' with the rpc method name!");
        }
        String methodName = root.get("method").asText();

        if (!root.has("type")) {
            throw new TException(
                    "Object must have field 'type' with the message type (CALL, REPLY, EXCEPTION, ONEWAY)!");
        }
        Byte messageType = TypedParser.TMESSAGE_TYPE.readFromJsonElement(root.get("type"));

        if (!root.has("args") || !root.get("args").isObject()) {
            throw new TException("Object must have field 'args' with the rpc method args!");
        }

        int sequenceId = root.has("seqid") ? root.get("seqid").asInt() : 0;

        // Override the root with the content of args - thrift's rpc reading will
        // proceed to read it as a message object.
        root = root.get("args");

        return new TMessage(methodName, messageType, sequenceId);
    }

    @Override
    public void readMessageEnd() throws TException {
        // We've already finished parsing the top level struct in
        // readMessageBegin, so nothing to do here.
        root = null;
    }

    @Override
    public TStruct readStructBegin() throws TException {
        getCurrentContext().read();

        JsonNode structElem;
        // Reading a new top level struct if the only item on the stack
        // is the BaseContext
        if (1 == contextStack.size()) {
            try {
                readRoot();
            } catch (IOException e) {
                throw new TException("Could not parse input, is it valid json?", e);
            }
            if (root == null) {
                throw new TException("parser.next() has nothing to parse!");
            }
            structElem = root;
        } else {
            structElem = getCurrentContext().getCurrentChild();
        }

        if (getCurrentContext().isMapKey()) {
            try {
                structElem = OBJECT_MAPPER.readTree(structElem.asText());
            } catch (IOException e) {
                throw new TException("Could not parse map key, is it valid json?", e);
            }
        }

        if (!structElem.isObject()) {
            throw new TException("Expected Json Object!");
        }

        Class<?> fieldClass = getCurrentFieldClassIfIs(TBase.class);
        if (fieldClass != null) {
            pushContext(new StructContext(structElem, fieldClass));
        } else {
            pushContext(new StructContext(structElem));
        }
        return ANONYMOUS_STRUCT;
    }

    @Override
    public void readStructEnd() throws TException {
        popContext();
    }

    @Override
    public TField readFieldBegin() throws TException {
        if (!getCurrentContext().hasMoreChildren()) {
            return new TField("", UNUSED_TYPE, (short) 0);
        }

        getCurrentContext().read();

        JsonNode jsonName = getCurrentContext().getCurrentChild();

        if (!jsonName.isTextual()) {
            throw new RuntimeException("Expected String for a field name");
        }

        String fieldName = jsonName.asText();
        currentFieldClass.push(getCurrentContext().getClassByFieldName(fieldName));

        return getCurrentContext().getTFieldByName(fieldName);
    }

    @Override
    public void readFieldEnd() throws TException {
        currentFieldClass.pop();
    }

    @Override
    public TMap readMapBegin() throws TException {
        getCurrentContext().read();

        JsonNode curElem = getCurrentContext().getCurrentChild();

        if (getCurrentContext().isMapKey()) {
            try {
                curElem = OBJECT_MAPPER.readTree(curElem.asText());
            } catch (IOException e) {
                throw new TException("Could not parse map key, is it valid json?", e);
            }
        }

        if (!curElem.isObject()) {
            throw new TException("Expected JSON Object!");
        }

        pushContext(new MapContext(curElem));

        return new TMap(UNUSED_TYPE, UNUSED_TYPE, curElem.size());
    }

    @Override
    public void readMapEnd() throws TException {
        popContext();
    }

    @Override
    public TList readListBegin() throws TException {
        int size = readSequenceBegin();
        return new TList(UNUSED_TYPE, size);
    }

    @Override
    public void readListEnd() throws TException {
        readSequenceEnd();
    }

    @Override
    public TSet readSetBegin() throws TException {
        int size = readSequenceBegin();
        return new TSet(UNUSED_TYPE, size);
    }

    @Override
    public void readSetEnd() throws TException {
        readSequenceEnd();
    }

    /**
     * Helper shared by read{List/Set}Begin.
     */
    private int readSequenceBegin() throws TException {
        getCurrentContext().read();
        if (getCurrentContext().isMapKey()) {
            throw new TException(SEQUENCE_AS_KEY_ILLEGAL);
        }

        JsonNode curElem = getCurrentContext().getCurrentChild();
        if (!curElem.isArray()) {
            throw new TException("Expected JSON Array!");
        }

        pushContext(new SequenceContext(curElem));
        return curElem.size();
    }

    /**
     * Helper shared by read{List/Set}End.
     */
    private void readSequenceEnd() {
        popContext();
    }

    @Override
    public boolean readBool() throws TException {
        return readNameOrValue(TypedParser.BOOLEAN);
    }

    @Override
    public byte readByte() throws TException {
        return readNameOrValue(TypedParser.BYTE);
    }

    @Override
    public short readI16() throws TException {
        return readNameOrValue(TypedParser.SHORT);
    }

    @Override
    public int readI32() throws TException {
        Class<?> fieldClass = getCurrentFieldClassIfIs(TEnum.class);
        if (fieldClass != null) {
            // Enum fields may be set by string, even though they represent integers.
            getCurrentContext().read();
            JsonNode elem = getCurrentContext().getCurrentChild();
            if (elem.isInt()) {
                return TypedParser.INTEGER.readFromJsonElement(elem);
            } else if (elem.isTextual()) {
                // All TEnum are enums
                @SuppressWarnings({ "unchecked", "rawtypes" })
                TEnum tEnum = (TEnum) Enum.valueOf((Class<Enum>) fieldClass,
                                                   TypedParser.STRING.readFromJsonElement(elem));
                return tEnum.getValue();
            } else {
                throw new TTransportException("invalid value type for enum field: " + elem.getNodeType() +
                                              " (" + elem + ')');
            }
        } else {
            return readNameOrValue(TypedParser.INTEGER);
        }
    }

    @Override
    public long readI64() throws TException {
        return readNameOrValue(TypedParser.LONG);
    }

    @Override
    public double readDouble() throws TException {
        return readNameOrValue(TypedParser.DOUBLE);
    }

    @Override
    public String readString() throws TException {
        return readNameOrValue(TypedParser.STRING);
    }

    @Override
    public ByteBuffer readBinary() throws TException {
        return readNameOrValue(TypedParser.BINARY);
    }

    /**
     * Read in a value of the given type, either as a name (meaning the
     * JSONElement is a string and we convert it), or as a value
     * (meaning the JSONElement has the type we expect).
     * Uses a TypedParser to do the real work.
     *
     * <p>TODO(Alex Roetter): not sure TypedParser is a win for the number of
     * lines it saves. Consider expanding out all the readX() methods to
     * do what readNameOrValue does, calling the relevant methods from
     * the TypedParser directly.
     */
    private <T> T readNameOrValue(TypedParser<T> ch) {
        getCurrentContext().read();

        JsonNode elem = getCurrentContext().getCurrentChild();
        if (getCurrentContext().isMapKey()) {
            // Will throw a ClassCastException if this is not a JsonPrimitive string
            return ch.readFromString(elem.asText());
        } else {
            return ch.readFromJsonElement(elem);
        }
    }

    /**
     * Read in the root node if it has not yet been read.
     */
    private void readRoot() throws IOException {
        if (root != null) {
            return;
        }
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        try {
            while (trans_.read(buffer, 0, READ_BUFFER_SIZE) > 0) {
                content.write(buffer);
            }
        } catch (TTransportException e) {
            if (TTransportException.END_OF_FILE != e.getType()) {
                throw new IOException(e);
            }
        }
        root = OBJECT_MAPPER.readTree(content.toByteArray());
    }

    /**
     * Return the current parsing context.
     */
    private BaseContext getCurrentContext() {
        return contextStack.peek();
    }

    /**
     * Add a new parsing context onto the parse context stack.
     */
    private void pushContext(BaseContext c) {
        contextStack.push(c);
    }

    /**
     * Pop a parsing context from the parse context stack.
     */
    private void popContext() {
        contextStack.pop();
    }

    /**
     * Return the current parsing context.
     */
    private JsonGenerator getCurrentWriter() {
        return writers.peek().writer;
    }

    private String getWriterString() throws TException {
        WriterByteArrayOutputStream wbaos = writers.peek();
        String ret;
        try {
            wbaos.writer.flush();
            ret = new String(wbaos.baos.toByteArray());
            wbaos.writer.close();
        } catch (IOException e) {
            throw new TException(e);
        }
        return ret;
    }

    private Class<?> getCurrentFieldClassIfIs(Class<?> classToMatch) {
        if (currentFieldClass.isEmpty() || currentFieldClass.peek() == null) {
            return null;
        }
        Class<?> classToCheck = currentFieldClass.peek();
        if (classToMatch.isAssignableFrom(classToCheck)) {
            return classToCheck;
        }
        return null;
    }

    private void pushWriter(ByteArrayOutputStream baos) {
        JsonGenerator generator;
        try {
            generator = OBJECT_MAPPER.getFactory().createGenerator(baos, JsonEncoding.UTF8)
                    .useDefaultPrettyPrinter();
        } catch (IOException e) {
            // Can't happen, using a byte stream.
            throw new IllegalStateException(e);
        }

        WriterByteArrayOutputStream wbaos = new WriterByteArrayOutputStream(generator, baos);
        writers.push(wbaos);
    }

    private void popWriter() {
        writers.pop();
    }

    private static final class WriterByteArrayOutputStream {
        final JsonGenerator writer;
        final ByteArrayOutputStream baos;

        private WriterByteArrayOutputStream(JsonGenerator writer, ByteArrayOutputStream baos) {
            this.writer = writer;
            this.baos = baos;
        }
    }

    /**
     * Factory.
     */
    public static class Factory implements TProtocolFactory {
        private static final long serialVersionUID = -5607714914895109618L;

        @Override
        public TProtocol getProtocol(TTransport trans) {
            return new TTextProtocol(trans);
        }
    }

    /**
     * Just a byte array output stream that forwards all data to
     * a TTransport when it is flushed or closed.
     */
    private class TTransportOutputStream extends ByteArrayOutputStream {
        // This isn't necessary, but a good idea to close the transport
        @Override
        public void close() throws IOException {
            flush();

            super.close();
            trans_.close();
        }

        @Override
        public void flush() throws IOException {
            try {
                super.flush();
                byte[] bytes = toByteArray();
                trans_.write(bytes);
                trans_.flush();
            } catch (TTransportException ex) {
                throw new IOException(ex);
            }
            // Clears the internal memory buffer, since we've already
            // written it out.
            reset();
        }
    }
}
