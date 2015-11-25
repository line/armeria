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


import java.io.IOException;

import com.google.gson.JsonElement;
import com.google.gson.stream.JsonWriter;

/**
 * A type parsing helper, knows how to parse a given type either from a string
 * or from a JsonElement, and knows how to emit a given type to a JsonWriter.
 *
 * Clients should use the static members defined here for common types.
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
    public Boolean readFromJsonElement(JsonElement elem) {
      return elem.getAsBoolean();
    }

    @Override
    public void writeValue(JsonWriter jw, Boolean val) throws IOException {
      jw.value(val);
    }
  };

  static final TypedParser<Byte> BYTE = new TypedParser<Byte>() {

    @Override
    public Byte readFromString(String s) {
      return Byte.parseByte(s);
    }

    @Override
    public Byte readFromJsonElement(JsonElement elem) {
      return elem.getAsByte();
    }

    @Override
    public void writeValue(JsonWriter jw, Byte val) throws IOException {
      jw.value(val);
    }
  };

  static final TypedParser<Short> SHORT = new TypedParser<Short>() {

    @Override
    public Short readFromString(String s) {
      return Short.parseShort(s);
    }

    @Override
    public Short readFromJsonElement(JsonElement elem) {
      return elem.getAsShort();
    }

    @Override
    public void writeValue(JsonWriter jw, Short val) throws IOException {
      jw.value(val);
    }
  };

  static final TypedParser<Integer> INTEGER = new TypedParser<Integer>() {

    @Override
    public Integer readFromString(String s) {
      return Integer.parseInt(s);
    }

    @Override
    public Integer readFromJsonElement(JsonElement elem) {
      return elem.getAsInt();
    }

    @Override
    public void writeValue(JsonWriter jw, Integer val) throws IOException {
      jw.value(val);
    }
  };
  static final TypedParser<Long> LONG = new TypedParser<Long>() {

    @Override
    public Long readFromString(String s) {
      return Long.parseLong(s);
    }

    @Override
    public Long readFromJsonElement(JsonElement elem) {
      return elem.getAsLong();
    }

    @Override
    public void writeValue(JsonWriter jw, Long val) throws IOException {
      jw.value(val);
    }
  };
  static final TypedParser<Double> DOUBLE = new TypedParser<Double>() {

    @Override
    public Double readFromString(String s) {
      return Double.parseDouble(s);
    }

    @Override
    public Double readFromJsonElement(JsonElement elem) {
      return elem.getAsDouble();
    }

    @Override
    public void writeValue(JsonWriter jw, Double val) throws IOException {
      jw.value(val);
    }
  };
  static final TypedParser<String> STRING = new TypedParser<String>() {

    @Override
    public String readFromString(String s) {
      return s;
    }

    @Override
    public String readFromJsonElement(JsonElement elem) {
      return elem.getAsString();
    }

    @Override
    public void writeValue(JsonWriter jw, String val) throws IOException {
      jw.value(val);
    }
  };

  /**
   * Convert from a string to the given type
   */
  abstract T readFromString(String s);

  /**
   * Read the given type from a JsonElement
   */
  abstract T readFromJsonElement(JsonElement elem);

  /**
   * Write the given type out using a JsonWriter
   */
  abstract void writeValue(JsonWriter jw, T val) throws IOException;
}
