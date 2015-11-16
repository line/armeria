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

package com.linecorp.armeria.server.docs;

import org.apache.thrift.protocol.TType;

enum ValueType {
    STOP,
    VOID,
    BOOL,
    BYTE,
    DOUBLE,
    I16,
    I32,
    I64,
    STRING,
    STRUCT,
    MAP,
    SET,
    LIST,
    ENUM;

    static ValueType of(byte type) {
        switch (type) {
        case TType.STOP:
            return ValueType.STOP;
        case TType.VOID:
            return ValueType.VOID;
        case TType.BOOL:
            return ValueType.BOOL;
        case TType.BYTE:
            return ValueType.BYTE;
        case TType.DOUBLE:
            return ValueType.DOUBLE;
        case TType.I16:
            return ValueType.I16;
        case TType.I32:
            return ValueType.I32;
        case TType.I64:
            return ValueType.I64;
        case TType.STRING:
            return ValueType.STRING;
        case TType.STRUCT:
            return ValueType.STRUCT;
        case TType.MAP:
            return ValueType.MAP;
        case TType.SET:
            return ValueType.SET;
        case TType.LIST:
            return ValueType.LIST;
        case TType.ENUM:
            return ValueType.ENUM;
        default:
            throw new IllegalArgumentException("unknown field value type: " + type);
        }
    }
}
