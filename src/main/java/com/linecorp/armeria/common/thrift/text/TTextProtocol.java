// =================================================================================================
// Copyright 2011 Twitter, Inc.
// -------------------------------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this work except in compliance with the License.
// You may obtain a copy of the License in the LICENSE file, or at:
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// =================================================================================================

package com.twitter.common.thrift.text;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.util.Stack;
import java.util.logging.Logger;

import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;
import com.google.gson.JsonElement;
import com.google.gson.JsonStreamParser;
import com.google.gson.stream.JsonWriter;

import org.apache.commons.codec.binary.Base64;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TList;
import org.apache.thrift.protocol.TMap;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.protocol.TSet;
import org.apache.thrift.protocol.TStruct;
import org.apache.thrift.protocol.TType;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;

/**
 * A simple text format for serializing/deserializing thrift
 * messages. This format is inefficient in space.
 *
 * For an example, see:
 * tests/resources/com/twitter/common/thrift/text/TTextProtocol_TestData.txt
 *
 * which is a text encoding of the thrift message defined in:
 *
 * src/main/thrift/com/twitter/common/thrift/text/TTextProtocolTest.thrift
 *
 * Whitespace (including newlines) is not significant.
 *
 * No comments are allowed in the json.
 *
 * We support parsing structs and anything embedded in a struct,
 * but not messages (which are generated as part of thrift RPC
 * service definitions).
 *
 * TODO(Alex Roetter): write a wrapper that allows us to read in a file
 * of many structs (perhaps stored in a JsonArray), passing each struct to
 * this class for parsing.
 *
 * See thrift's @see org.apache.thrift.protocol.TJSONProtocol
 * for another example an implementation of the @see TProtocol
 * interface. This class is based on that.
 *
 * TODO(Alex Roetter): Also add a new TEXT_PROTOCOL field to ThriftCodec
 *
 * TODO(Alex Roetter): throw this up on my github. Seems generally useful.
 *
 * TODO(Alex Roetter): add support for enums
 *
 */
public class TTextProtocol extends TProtocol {
  private static final Logger LOG = Logger.getLogger(
      TTextProtocol.class.getName());
  private static final String SEQUENCE_AS_KEY_ILLEGAL =
      "Can't have a sequence (list or set) as a key in a map!";

  private static final TStruct ANONYMOUS_STRUCT = new TStruct();

  // how many bytes to read at once
  private static final int READ_BUFFER_SIZE = 1024;

  private static final byte UNUSED_TYPE = TType.STOP;

  private Stack<BaseContext> contextStack;
  private Base64 base64Encoder = new Base64();
  private final Stack<WriterByteArrayOutputStream> writers;
  private JsonStreamParser parser;

  private static class WriterByteArrayOutputStream {
    final JsonWriter writer;
    final ByteArrayOutputStream baos;

    public WriterByteArrayOutputStream(JsonWriter writer, ByteArrayOutputStream baos) {
      this.writer = writer;
      this.baos = baos;
    }
  }

  /**
   * Factory
   */
  public static class Factory implements TProtocolFactory {
    @Override
    public TProtocol getProtocol(TTransport trans) {
      return new TTextProtocol(trans);
    }
  }

  /**
   * Create a parser which can read from trans, and create the output writer
   * that can write to a TTransport
   */
  public TTextProtocol(TTransport trans) {
    super(trans);

    writers = new Stack<WriterByteArrayOutputStream>();

    ByteArrayOutputStream mybaos = new TTransportOutputStream();
    pushWriter(mybaos);

    reset();

    try {
      parser = createParser();
    } catch (IOException ex) {
      // This happens when we're created in write mode (i.e.
      // there is nothing to parse). Calls to read methods will fail.
    }
    contextStack = new Stack<BaseContext>();
    contextStack.push(new BaseContext());
  }

  @Override
  public final void reset() {
  }

  /**
   * I believe these two messages are called for a thrift service
   * interface. We don't plan on storing any text objects of that
   * type on disk.
   */
  @Override
  public void writeMessageBegin(TMessage message) throws TException {
    unsupportedOperation();
  }

  @Override
  public void writeMessageEnd() throws TException {
    unsupportedOperation();
  }

  /**
   * Throws an exception for invoked operations that we don't support.
   */
  private void unsupportedOperation() {
    throw new UnsupportedOperationException("Not supported yet.");
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
      getCurrentWriter().name(field.name);
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
      getCurrentWriter().beginObject();
    } catch (IOException ex) {
      throw new TException(ex);
    }
  }

  /**
   * Helper to write out the end of a Thrift type (either struct or map),
   * both of which are written as JsonObjects.
   * @throws TException
   */
  private void writeJsonObjectEnd() throws TException {
    try {
      getCurrentWriter().endObject();
      popContext();
      if (getCurrentContext().isMapKey()) {
        String writerString = getWriterString();
        popWriter();
        getCurrentWriter().name(writerString);
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
   * Helper shared by write{List/Set}Begin
   */
  private void writeSequenceBegin(int size) throws TException {
    getCurrentContext().write();
    if (getCurrentContext().isMapKey()) {
      throw new TException(SEQUENCE_AS_KEY_ILLEGAL);
    }
    pushContext(new SequenceContext(null));

    try {
      getCurrentWriter().beginArray();
    } catch (IOException ex) {
      throw new TTransportException(ex);
    }
  }

  /**
   * Helper shared by write{List/Set}End
   * @throws TException
   */
  private void writeSequenceEnd() throws TException {
    try {
      getCurrentWriter().endArray();
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
    writeString(new String(base64Encoder.encode(buf.array())));
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
        getCurrentWriter().name(val.toString());
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
    unsupportedOperation();
    return null;
  }

  @Override
  public void readMessageEnd() throws TException {
    unsupportedOperation();
  }

  @Override
  public TStruct readStructBegin() throws TException {
    getCurrentContext().read();

    JsonElement structElem;
    // Reading a new top level struct if the only item on the stack
    // is the BaseContext
    if (1 == contextStack.size()) {
      structElem = parser.next();
      if (null == structElem) {
        throw new TException("parser.next() has nothing to parse!");
      }
    } else {
      structElem = getCurrentContext().getCurrentChild();
    }

    if (getCurrentContext().isMapKey()) {
      structElem = new JsonStreamParser(structElem.getAsString()).next();
    }

    if (!structElem.isJsonObject()) {
      throw new TException("Expected Json Object!");
    }

    pushContext(new StructContext(structElem.getAsJsonObject()));
    return ANONYMOUS_STRUCT;
  }

  @Override
  public void readStructEnd() throws TException {
    popContext();
  }

  @Override
  public TField readFieldBegin() throws TException {
    String name = null;

    if (!getCurrentContext().hasMoreChildren()) {
      return new TField("", UNUSED_TYPE, (short) 0);
    }

    getCurrentContext().read();

    JsonElement jsonName = getCurrentContext().getCurrentChild();

    if (!jsonName.getAsJsonPrimitive().isString()) {
      throw new RuntimeException("Expected String for a field name");
    }

    return getCurrentContext().getTFieldByName(
        jsonName.getAsJsonPrimitive().getAsString());
  }

  @Override
  public void readFieldEnd() throws TException {
  }

  @Override
  public TMap readMapBegin() throws TException {
    getCurrentContext().read();

    JsonElement curElem = getCurrentContext().getCurrentChild();

    if (getCurrentContext().isMapKey()) {
      curElem = new JsonStreamParser(curElem.getAsString()).next();
    }

    if (!curElem.isJsonObject()) {
      throw new TException("Expected JSON Object!");
    }

    pushContext(new MapContext(curElem.getAsJsonObject()));

    return new TMap(UNUSED_TYPE, UNUSED_TYPE,
        curElem.getAsJsonObject().entrySet().size());
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
   * Helper shared by read{List/Set}Begin
   */
  private int readSequenceBegin() throws TException {
    getCurrentContext().read();
    if (getCurrentContext().isMapKey()) {
      throw new TException(SEQUENCE_AS_KEY_ILLEGAL);
    }

    JsonElement curElem = getCurrentContext().getCurrentChild();
    if (!curElem.isJsonArray()) {
      throw new TException("Expected JSON Array!");
    }

    pushContext(new SequenceContext(curElem.getAsJsonArray()));
    return curElem.getAsJsonArray().size();
  }

  /**
   * Helper shared by read{List/Set}End
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
    return readNameOrValue(TypedParser.INTEGER);
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
    return ByteBuffer.wrap(base64Encoder.decode(readString()));
  }

  /**
   * Read in a value of the given type, either as a name (meaning the
   * JSONElement is a string and we convert it), or as a value
   * (meaning the JSONElement has the type we expect).
   * Uses a TypedParser to do the real work.
   *
   * TODO(Alex Roetter): not sure TypedParser is a win for the number of
   * lines it saves. Consider expanding out all the readX() methods to
   * do what readNameOrValue does, calling the relevant methods from
   * the TypedParser directly.
   */
  private <T> T readNameOrValue(TypedParser<T> ch) {
    getCurrentContext().read();

    JsonElement elem = getCurrentContext().getCurrentChild();
    if (getCurrentContext().isMapKey()) {
      // Will throw a ClassCastException if this is not a JsonPrimitive string
      return ch.readFromString(elem.getAsString());
    } else {
      return ch.readFromJsonElement(elem);
    }
  }


  /**
   * Set up the stream parser to read from the trans_ TTransport
   * buffer.
   */
  private JsonStreamParser createParser() throws IOException {
    return new JsonStreamParser(
        new String(ByteStreams.toByteArray(new InputStream() {
          private int index;
          private int max;
          private final byte[] buffer = new byte[READ_BUFFER_SIZE];

          @Override
          public int read() throws IOException {
            if (max == -1) {
              return -1;
            }
            if (max > 0 && index < max) {
              return buffer[index++];
            }
            try {
              max = trans_.read(buffer, 0, READ_BUFFER_SIZE);
              index = 0;
            } catch (TTransportException e) {
              if (TTransportException.END_OF_FILE != e.getType()) {
                throw new IOException(e);
              }
              max = -1;
            }
            return read();
          }
        }), Charsets.UTF_8));
  }


  /**
   * Return the current parsing context
   */
  private BaseContext getCurrentContext() {
    return contextStack.peek();
  }

  /**
   * Add a new parsing context onto the parse context stack
   */
  private void pushContext(BaseContext c) {
    contextStack.push(c);
  }

  /**
   * Pop a parsing context from the parse context stack
   */
  private void popContext() {
    contextStack.pop();
  }

  /**
   * Return the current parsing context
   */
  private JsonWriter getCurrentWriter() {
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

  private void pushWriter(ByteArrayOutputStream baos) {
    OutputStreamWriter osw = new OutputStreamWriter(baos, Charsets.UTF_8);

    JsonWriter writer = new JsonWriter(osw);
    writer.setIndent("  ");  // two spaces

    WriterByteArrayOutputStream wbaos = new WriterByteArrayOutputStream(writer, baos);
    writers.push(wbaos);
  }

  private void popWriter() {
    writers.pop();
  }

  /** Just a byte array output stream that forwards all data to
   * a TTransport when it is flushed or closed
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
      super.reset();
    }
  }
}
