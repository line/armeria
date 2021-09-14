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

import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.Calendar;
import java.util.Date;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.StringUtil;

import io.netty.handler.codec.DateFormatter;
import io.netty.handler.codec.ValueConverter;

/**
 * Converts to/from native types, general {@link Object}, and {@link CharSequence}s.
 */
final class StringValueConverter implements ValueConverter<String> {

    // Forked from Netty 4.1.34 at 1611acf4cee4481b89a2cf024ccf821de2dbf13c (CharSequenceValueConverter) and
    // 4c64c98f348131e0792ba4a92ce3d0003237d56a (DefaultHttpHeaders.HeaderValueConverter)

    static final StringValueConverter INSTANCE = new StringValueConverter();

    private StringValueConverter() {}

    @Nullable
    @Override
    @SuppressWarnings("UseOfObsoleteDateTimeApi")
    public String convertObject(@Nullable Object value) {
        if (value == null) {
            return null;
        }

        // Try the types that appears more often first.
        if (value instanceof CharSequence ||
            value instanceof Number ||
            value instanceof MediaType) {
            return value.toString();
        }

        if (value instanceof Instant) {
            return DateFormatter.format(new Date(((Instant) value).toEpochMilli()));
        }

        if (value instanceof TemporalAccessor) {
            return DateFormatter.format(new Date(Instant.from((TemporalAccessor) value).toEpochMilli()));
        }

        if (value instanceof CacheControl) {
            return ((CacheControl) value).asHeaderValue();
        }

        if (value instanceof ContentDisposition) {
            return ((ContentDisposition) value).asHeaderValue();
        }

        // Obsolete types.
        if (value instanceof Date) {
            return DateFormatter.format((Date) value);
        }

        if (value instanceof Calendar) {
            return DateFormatter.format(((Calendar) value).getTime());
        }

        return value.toString();
    }

    @Override
    public String convertInt(int value) {
        return StringUtil.toString(value);
    }

    @Override
    public String convertLong(long value) {
        return StringUtil.toString(value);
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
        return StringUtil.toString(value & 0xFF);
    }

    @Override
    public byte convertToByte(String value) {
        if (!value.isEmpty()) {
            return (byte) value.charAt(0);
        }
        return Byte.parseByte(value);
    }

    @Override
    public char convertToChar(String value) {
        return value.charAt(0);
    }

    @Override
    public String convertShort(short value) {
        return StringUtil.toString(value);
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
        return DateFormatter.format(new Date(value));
    }

    @Override
    public long convertToTimeMillis(String value) {
        @SuppressWarnings("UseOfObsoleteDateTimeApi")
        Date date = null;
        try {
            date = DateFormatter.parseHttpDate(value);
        } catch (Exception ignored) {
            // `parseHttpDate()` can raise an exception rather than returning `null`
            // when the given value has more than 64 characters.
        }

        if (date == null) {
            throw new IllegalArgumentException("not a date: " + value);
        }
        return date.getTime();
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
