/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.thrift;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.thrift.TBase;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.meta_data.FieldValueMetaData;
import org.apache.thrift.meta_data.StructMetaData;
import org.apache.thrift.protocol.TType;

/**
 * Utility methods for use by both {@code com.linecorp.armeria.client.thrift} and
 * {@code com.linecorp.armeria.server.thrift}.
 */
public final class ThriftUtil {

    /**
     * Converts the specified Thrift {@code seqId} to a hexadecimal {@link String}.
     */
    public static String seqIdToString(int seqId) {
        return Long.toString(seqId & 0xFFFFFFFFL, 16);
    }

    /**
     * Converts the specified Thrift call parameters to a list of Java objects.
     */
    public static List<Object> toJavaParams(TBase<TBase<?, ?>, TFieldIdEnum> params) {
        return Collections.unmodifiableList(
                FieldMetaData.getStructMetaDataMap(params.getClass()).keySet().stream()
                             .map(params::getFieldValue).collect(Collectors.toList()));
    }

    /**
     * Converts the specified {@link FieldValueMetaData} into its corresponding Java type.
     */
    public static Class<?> toJavaType(FieldValueMetaData metadata) {
        switch (metadata.type) {
        case TType.BOOL:
            return Boolean.class;
        case TType.BYTE:
            return Byte.class;
        case TType.DOUBLE:
            return Double.class;
        case TType.ENUM:
            return Enum.class;
        case TType.I16:
            return Short.class;
        case TType.I32:
            return Integer.class;
        case TType.I64:
            return Long.class;
        case TType.LIST:
            return List.class;
        case TType.MAP:
            return Map.class;
        case TType.SET:
            return Set.class;
        case TType.STRING:
            return String.class;
        case TType.STRUCT:
            return ((StructMetaData) metadata).structClass;
        case TType.VOID:
            return Void.class;
        }

        // Should never reach here.
        throw new Error();
    }

    private ThriftUtil() {}
}
