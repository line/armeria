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

package com.linecorp.armeria.internal.common.thrift;

import java.nio.ByteBuffer;

import org.apache.thrift.TBase;
import org.apache.thrift.TFieldIdEnum;

/**
 * Provides access to a Thrift field.
 */
public final class ThriftFieldAccess {

    /**
     * Gets the value of the specified struct field.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static Object get(TBase<?, ?> struct, TFieldIdEnum field) {
        final Object value = ((TBase) struct).getFieldValue(field);
        if (value instanceof byte[]) {
            return ByteBuffer.wrap((byte[]) value);
        } else {
            return value;
        }
    }

    /**
     * Sets the value of the specified struct field.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static void set(TBase<?, ?> struct, TFieldIdEnum field, Object value) {
        ((TBase) struct).setFieldValue(field, value);
    }

    /**
     * Tells whether the specified struct field is set or not.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static boolean isSet(TBase<?, ?> struct, TFieldIdEnum field) {
        return ((TBase) struct).isSet(field);
    }

    private ThriftFieldAccess() {}
}
