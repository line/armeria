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
/*
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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
package com.linecorp.armeria.common;

import io.netty.handler.codec.ValueConverter;

/**
 * Converts to/from native types, general {@link Object}, and {@link CharSequence}s.
 */
final class StringValueConverter implements ValueConverter<String> {

    static final StringValueConverter INSTANCE = new StringValueConverter();

    private StringValueConverter() {}

    @Override
    public String convertObject(Object value) {
        if (value == null) {
            return null;
        }

        return value.toString();
    }

    @Override
    public String convertInt(int value) {
        return String.valueOf(value);
    }

    @Override
    public String convertLong(long value) {
        return String.valueOf(value);
    }

    @Override
    public String convertDouble(double value) {
        return String.valueOf(value);
    }

    @Override
    public String convertChar(char value) {
        return String.valueOf(value);
    }

    @Override
    public String convertBoolean(boolean value) {
        return String.valueOf(value);
    }

    @Override
    public String convertFloat(float value) {
        return String.valueOf(value);
    }

    @Override
    public boolean convertToBoolean(String value) {
        return Boolean.parseBoolean(value);
    }

    @Override
    public String convertByte(byte value) {
        return String.valueOf(value & 0xFF);
    }

    @Override
    public byte convertToByte(String value) {
        return (byte) value.charAt(0);
    }

    @Override
    public char convertToChar(String value) {
        return value.charAt(0);
    }

    @Override
    public String convertShort(short value) {
        return String.valueOf(value);
    }

    @Override
    public short convertToShort(String value) {
        return Short.valueOf(value);
    }

    @Override
    public int convertToInt(String value) {
        return Integer.parseInt(value);
    }

    @Override
    public long convertToLong(String value) {
        return Long.parseLong(value);
    }

    @Override
    public String convertTimeMillis(long value) {
        return HeaderDateTimeFormat.format(value);
    }

    @Override
    public long convertToTimeMillis(String value) {
        return HeaderDateTimeFormat.parse(value).toEpochMilli();
    }

    @Override
    public float convertToFloat(String value) {
        return Float.valueOf(value);
    }

    @Override
    public double convertToDouble(String value) {
        return Double.valueOf(value);
    }
}
