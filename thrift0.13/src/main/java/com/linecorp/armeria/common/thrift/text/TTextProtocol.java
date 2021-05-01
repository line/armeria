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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Stack;

import javax.annotation.Nullable;

import org.apache.thrift.TBase;
import org.apache.thrift.TEnum;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TJSONProtocol;
import org.apache.thrift.protocol.TList;
import org.apache.thrift.protocol.TMap;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
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
import com.google.common.primitives.Ints;

import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * A simple text format for serializing/deserializing Thrift
 * messages. This format is inefficient in space.
 *
 * <p>For an example, see:
 * {@code test/resources/com/linecorp/armeria/common/thrift/text/TTextProtocol_TestData.txt},</p>
 *
 * <p>which is a text encoding of the Thrift message defined in:
 * {@code test/thrift/TTextProtocolTest.thrift}.</p>
 *
 * <p>Whitespace (including newlines) is not significant.
 * No comments are allowed in the JSON.</p>
 *
 * <p>Messages must be formatted as a JSON object with {@code method} field containing
 * the message name, {@code type} containing the message type as an uppercase string
 * corresponding to {@link TMessageType}, {@code args} containing a JSON object with
 * the actual arguments, and an optional {@code seqid} field containing the sequence
 * ID. If {@code seqid} is not provided, it will be treated as {@code 0}. {@code args}
 * should use the argument names as defined in the service definition.</p>
 *
 * <p>Example:<pre>{@code
 * {
 *     "method": "GetItem",
 *     "type": "CALL",
 *     "args": {
 *         "id": 1,
 *         "fetchAll": true
 *     },
 *     "seqid": 100
 * }
 * }</pre></p>
 *
 * <p>See Thrift's {@link TJSONProtocol} for another example of an implementation
 * of the {@link TProtocol} interface. This class is based on that.</p>
 *
 * <p>TODO(Alex Roetter): write a wrapper that allows us to read in a file
 * of many structs (perhaps stored in a JsonArray), passing each struct to
 * this class for parsing.
 *
 * <p>TODO: Support string values for enums that have been typedef'd.
 *
 * @see TTextProtocolFactory#getProtocol(TTransport)
 */
final class TTextProtocol extends TProtocol {

    static final String MAP_KEY_SUFFIX = "$k";
    static final String MAP_VALUE_SUFFIX = "$v";

    private static final String SEQUENCE_AS_KEY_ILLEGAL =
            "Can't have a sequence (list or set) as a key in a map!";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);

    private static final TStruct ANONYMOUS_STRUCT = new TStruct();

    // How many bytes to read at once.
    private static final int READ_BUFFER_SIZE = 1024;
    private static final byte UNUSED_TYPE = TType.STOP;

    private final Stack<WriterByteArrayOutputStream> writers;
    private final Stack<BaseContext> contextStack;
    private final Stack<BaseContext> currentFieldContext;
    private final Stack<String> currentFieldName;
    private final boolean useNamedEnums;
    @Nullable
    private JsonNode root;

    /**
     * Create a parser which can read from {@code trans},
     * and create the output writer that can write to a {@link TTransport}.
     */
    TTextProtocol(TTransport trans) {
        this(trans, false);
    }

    /**
     * Create a parser which can read from {@code trans}, and create the output writer
     * that can write to a {@link TTransport}, optionally enabling serialization of named enums.
     */
    TTextProtocol(TTransport trans, boolean useNamedEnums) {
        super(trans);

        writers = new Stack<>();
        contextStack = new Stack<>();
        currentFieldContext = new Stack<>();
        currentFieldName = new Stack<>();
        this.useNamedEnums = useNamedEnums;
        reset();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Class<? extends IScheme> getScheme() {
        return StandardScheme.class;
    }

    @Override
    public void reset() {
        root = null;

        writers.clear();
        pushWriter(new TTransportOutputStream());

        contextStack.clear();
        contextStack.push(new BaseContext());
        currentFieldContext.clear();
        currentFieldName.clear();
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
        writeCurrentContext();
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

        currentFieldContext.push(getCurrentContext());
        currentFieldName.push(field.name);
    }

    @Override
    public void writeFieldEnd() throws TException {
        currentFieldContext.pop();
        currentFieldName.pop();
    }

    @Override
    public void writeFieldStop() throws TException {
    }

    @Override
    public void writeMapBegin(TMap map) throws TException {
        writeCurrentContext();
        currentFieldName.push(currentFieldName.peek());
        writeJsonObjectBegin(new MapContext(null));
    }

    @Override
    public void writeMapEnd() throws TException {
        writeJsonObjectEnd();
        currentFieldName.pop();
    }

    /**
     * Helper to write out the beginning of a Thrift type (either struct or map),
     * both of which are written as JsonObjects.
     */
    private void writeJsonObjectBegin(BaseContext context) throws TException {
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
                final String writerString = getWriterString();
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
        writeSequenceBegin();
    }

    @Override
    public void writeListEnd() throws TException {
        writeSequenceEnd();
    }

    @Override
    public void writeSetBegin(TSet set) throws TException {
        writeSequenceBegin();
    }

    @Override
    public void writeSetEnd() throws TException {
        writeListEnd();
    }

    /**
     * Helper shared by write{List/Set}Begin.
     */
    private void writeSequenceBegin() throws TException {
        writeCurrentContext();
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
        writeCurrentContext();
        writeNameOrValue(TypedParser.BOOLEAN, b);
    }

    @Override
    public void writeByte(byte b) throws TException {
        writeCurrentContext();
        writeNameOrValue(TypedParser.BYTE, b);
    }

    @Override
    public void writeI16(short i16) throws TException {
        writeCurrentContext();
        writeNameOrValue(TypedParser.SHORT, i16);
    }

    @Override
    public void writeI32(int i32) throws TException {
        writeCurrentContext();

        if (!useNamedEnums) {
            writeNameOrValue(TypedParser.INTEGER, i32);
            return;
        }

        final Class<?> fieldClass = getCurrentFieldClassIfIs(TEnum.class);
        if (fieldClass == null) {
            writeNameOrValue(TypedParser.INTEGER, i32);
            return;
        }

        try {
            final Method method = fieldClass.getMethod("findByValue", int.class);
            final String str = method.invoke(null, i32).toString();
            writeNameOrValue(TypedParser.STRING, str);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new TTransportException("invalid value for enum field " +
                                          fieldClass.getSimpleName() + ':' + i32);
        }
    }

    @Override
    public void writeI64(long i64) throws TException {
        writeCurrentContext();
        writeNameOrValue(TypedParser.LONG, i64);
    }

    @Override
    public void writeDouble(double dub) throws TException {
        writeCurrentContext();
        writeNameOrValue(TypedParser.DOUBLE, dub);
    }

    @Override
    public void writeString(String str) throws TException {
        writeCurrentContext();
        writeNameOrValue(TypedParser.STRING, str);
    }

    @Override
    public void writeBinary(ByteBuffer buf) throws TException {
        writeCurrentContext();
        writeNameOrValue(TypedParser.BINARY, buf);
    }

    /**
     * Write out the given value, either as a JSON name (meaning it's
     * escaped by quotes), or a value. The TypedParser knows how to
     * handle the writing.
     */
    private <T> void writeNameOrValue(TypedParser<T> helper, T val) throws TException {
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
        final String methodName = root.get("method").asText();

        if (!root.has("type")) {
            throw new TException(
                    "Object must have field 'type' with the message type (CALL, REPLY, EXCEPTION, ONEWAY)!");
        }
        final Byte messageType = TypedParser.TMESSAGE_TYPE.readFromJsonElement(root.get("type"));

        if (!root.has("args") || !root.get("args").isObject()) {
            throw new TException("Object must have field 'args' with the rpc method args!");
        }

        final int sequenceId = root.has("seqid") ? root.get("seqid").asInt() : 0;

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
        readCurrentContext();

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

        final Class<?> fieldClass = getCurrentFieldClassIfIs(TBase.class);
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

        readCurrentContext();

        final JsonNode jsonName = getCurrentContext().getCurrentChild();

        if (!jsonName.isTextual()) {
            throw new RuntimeException("Expected String for a field name");
        }

        final String fieldName = jsonName.asText();
        currentFieldContext.push(getCurrentContext());
        currentFieldName.push(fieldName);

        return getCurrentContext().getTFieldByName(fieldName);
    }

    @Override
    public void readFieldEnd() throws TException {
        currentFieldContext.pop();
        currentFieldName.pop();
    }

    @Override
    public TMap readMapBegin() throws TException {
        readCurrentContext();
        currentFieldName.push(currentFieldName.peek());

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
        currentFieldName.pop();
    }

    @Override
    public TList readListBegin() throws TException {
        final int size = readSequenceBegin();
        return new TList(UNUSED_TYPE, size);
    }

    @Override
    public void readListEnd() throws TException {
        readSequenceEnd();
    }

    @Override
    public TSet readSetBegin() throws TException {
        final int size = readSequenceBegin();
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
        readCurrentContext();
        if (getCurrentContext().isMapKey()) {
            throw new TException(SEQUENCE_AS_KEY_ILLEGAL);
        }

        final JsonNode curElem = getCurrentContext().getCurrentChild();
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
        readCurrentContext();
        return readNameOrValue(TypedParser.BOOLEAN);
    }

    @Override
    public byte readByte() throws TException {
        readCurrentContext();
        return readNameOrValue(TypedParser.BYTE);
    }

    @Override
    public short readI16() throws TException {
        readCurrentContext();
        return readNameOrValue(TypedParser.SHORT);
    }

    @Override
    public int readI32() throws TException {
        readCurrentContext();

        final Class<?> fieldClass = getCurrentFieldClassIfIs(TEnum.class);
        if (fieldClass != null) {
            // Enum fields may be set by string, even though they represent integers.
            final JsonNode elem = getCurrentContext().getCurrentChild();
            if (elem.isInt() || Ints.tryParse(elem.asText()) != null) {
                return TypedParser.INTEGER.readFromJsonElement(elem);
            } else if (elem.isTextual()) {
                // All TEnum are enums
                @SuppressWarnings({ "unchecked", "rawtypes" })
                final TEnum tEnum = (TEnum) Enum.valueOf((Class<Enum>) fieldClass,
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
        readCurrentContext();
        return readNameOrValue(TypedParser.LONG);
    }

    @Override
    public double readDouble() throws TException {
        readCurrentContext();
        return readNameOrValue(TypedParser.DOUBLE);
    }

    @Override
    public String readString() throws TException {
        readCurrentContext();
        return readNameOrValue(TypedParser.STRING);
    }

    @Override
    public ByteBuffer readBinary() throws TException {
        readCurrentContext();
        return readNameOrValue(TypedParser.BINARY);
    }

    /**
     * Read in a value of the given type, either as a name (meaning the
     * JSONElement is a string and we convert it), or as a value
     * (meaning the JSONElement has the type we expect).
     * Uses a TypedParser to do the real work.
     */
    private <T> T readNameOrValue(TypedParser<T> ch) {
        final JsonNode elem = getCurrentContext().getCurrentChild();
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
        final ByteArrayOutputStream content = new ByteArrayOutputStream();
        final TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.get();
        final byte[] buffer = tempThreadLocals.byteArray(READ_BUFFER_SIZE);
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
        tempThreadLocals.releaseByteArray();
    }

    /**
     * Return the current parsing context.
     */
    private BaseContext getCurrentContext() {
        return contextStack.peek();
    }

    /**
     * Prepare the current parsing context for writing.
     */
    private void writeCurrentContext() {
        getCurrentContext().write();
        updateCurrentFieldName();
    }

    /**
     * Prepare the current parsing context for reading.
     */
    private void readCurrentContext() {
        getCurrentContext().read();
        updateCurrentFieldName();
    }

    /**
     * Update the current field name, if necessary.
     *
     * <p>After every read/write operation in {@link MapContext},
     * the field name should toggle between the map's key and value.</p>
     */
    private void updateCurrentFieldName() {
        if (getCurrentContext() instanceof MapContext) {
            currentFieldName.pop();
            final String suffix = getCurrentContext().isMapKey() ? MAP_KEY_SUFFIX : MAP_VALUE_SUFFIX;
            currentFieldName.push(currentFieldName.peek() + suffix);
        }
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
        final WriterByteArrayOutputStream wbaos = writers.peek();
        final String ret;
        try {
            wbaos.writer.flush();
            ret = new String(wbaos.baos.toByteArray());
            wbaos.writer.close();
        } catch (IOException e) {
            throw new TException(e);
        }
        return ret;
    }

    @Nullable
    private Class<?> getCurrentFieldClassIfIs(Class<?> classToMatch) {
        if (currentFieldContext.isEmpty() || currentFieldContext.peek() == null) {
            return null;
        }

        final BaseContext context = currentFieldContext.peek();
        final String fieldName = currentFieldName.peek();

        final Class<?> classToCheck = context.getClassByFieldName(fieldName);
        if (classToCheck != null && classToMatch.isAssignableFrom(classToCheck)) {
            return classToCheck;
        }
        return null;
    }

    private void pushWriter(ByteArrayOutputStream baos) {
        final JsonGenerator generator;
        try {
            generator = OBJECT_MAPPER.getFactory().createGenerator(baos, JsonEncoding.UTF8)
                                     .useDefaultPrettyPrinter();
        } catch (IOException e) {
            // Can't happen, using a byte stream.
            throw new IllegalStateException(e);
        }

        final WriterByteArrayOutputStream wbaos = new WriterByteArrayOutputStream(generator, baos);
        writers.push(wbaos);
    }

    private void popWriter() {
        writers.pop();
    }

    /**
     * Returns the minimum number of bytes a type will consume on the wire.
     *
     * <p>This API is added to TProtocol in Thrift 0.14.0.
     * Forked from https://github.com/apache/thrift/blob/7054b315f4fc84d95461268a5e47b67f4ff6801d/lib/java/src/org/apache/thrift/protocol/TJSONProtocol.java#L989
     */
    @SuppressWarnings("unused")
    public int getMinSerializedSize(byte type) throws TException {
        switch (type) {
            case 0:
                return 0; // Stop
            case 1:
                return 0; // Void
            case 2:
                return 1; // Bool
            case 3:
                return 1; // Byte
            case 4:
                return 1; // Double
            case 6:
                return 1; // I16
            case 8:
                return 1; // I32
            case 10:
                return 1;// I64
            case 11:
                return 2;  // string length
            case 12:
                return 2;  // empty struct
            case 13:
                return 2;  // element count Map
            case 14:
                return 2;  // element count Set
            case 15:
                return 2;  // element count List
            default:
                throw new TTransportException(TTransportException.UNKNOWN, "unrecognized type code");
        }
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
                final byte[] bytes = toByteArray();
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
