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

import static com.linecorp.armeria.common.thrift.text.AbstractThriftMessageClassFinder.isTBase;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Supplier;

import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.TFieldRequirementType;
import org.apache.thrift.meta_data.EnumMetaData;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.meta_data.FieldValueMetaData;
import org.apache.thrift.meta_data.ListMetaData;
import org.apache.thrift.meta_data.MapMetaData;
import org.apache.thrift.meta_data.SetMetaData;
import org.apache.thrift.meta_data.StructMetaData;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.MapMaker;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.thrift.ThriftMetadataAccess;

/**
 * A struct parsing context. Builds a map from field name to TField.
 *
 * @author Alex Roetter
 */
final class StructContext extends PairContext {
    private static final Logger log = LoggerFactory.getLogger(StructContext.class);
    private static final Supplier<Class<?>> thriftMessageClassFinder;
    private static final Map<String, Class<?>> fieldMetaDataClassCache = new MapMaker().weakValues().makeMap();

    static {
        Supplier<Class<?>> supplier = null;
        if (SystemInfo.javaVersion() >= 9) {
            try {
                supplier = new StackWalkingThriftMessageClassFinder();
            } catch (Throwable t) {
                log.warn("Failed to initialize StackWalkingThriftMessageClassFinder. " +
                         "Falling back to DefaultThriftMessageClassFinder:", t);
            }
        }

        thriftMessageClassFinder = supplier != null ? supplier : new DefaultThriftMessageClassFinder();
    }

    // When processing a given thrift struct, we need certain information
    // for every field in that struct. We store that here, in a map
    // from fieldName (a string) to a TField object describing that
    // field.
    private final Map<String, TField> fieldNameMap;

    private final Map<String, Class<?>> classMap;

    /**
     * Build the name -> TField map.
     */
    StructContext(@Nullable JsonNode json) {
        this(json, getCurrentThriftMessageClass());
    }

    StructContext(@Nullable JsonNode json, Class<?> clazz) {
        super(json);
        classMap = new HashMap<>();
        fieldNameMap = computeFieldNameMap(clazz);
    }

    @Override
    protected TField getTFieldByName(String name) throws TException {
        if (!fieldNameMap.containsKey(name)) {
            throw new TException("Unknown field: " + name);
        }
        return fieldNameMap.get(name);
    }

    @Override
    @Nullable
    protected Class<?> getClassByFieldName(String fieldName) {
        return classMap.get(fieldName);
    }

    /**
     * I need to know what type thrift message we are processing,
     * in order to look up fields by their field name. For example,
     * i I parse a line "count : 7", I need to know we are in a
     * StatsThriftMessage, or similar, to know that count should be
     * of type int32, and have a thrift id 1.
     *
     * <p>In order to figure this out, I assume that this method was
     * called (indirectly) by the read() method in a class T which
     * is a TBase subclass. It is called that way by thrift generated
     * code. So, I iterate backwards up the call stack, stopping
     * at the first method call which belongs to a TBase object.
     * I return the Class for that object.
     *
     * <p>One could argue this is someone fragile and error prone.
     * The alternative is to modify the thrift compiler to generate
     * code which passes class information into this (and other)
     * TProtocol objects, and that seems like a lot more work. Given
     * the low level interface of TProtocol (e.g. methods like readInt(),
     * rather than readThriftMessage()), it seems likely that a TBase
     * subclass, which has the knowledge of what fields exist, as well as
     * their types & relationships, will have to be the caller of
     * the TProtocol methods.
     *
     * <p>Note: this approach does not handle TUnion, because TUnion has its own implementation of
     * read/write and any TUnion thrift structure does not override its read and write method.
     * Thus this algorithm fail to get current specific TUnion thrift structure by reading the stack.
     * To fix this, we can track call stack of nested thrift objects on our own by overriding
     * TProtocol.writeStructBegin(), rather than relying on the stack trace.
     */
    private static Class<?> getCurrentThriftMessageClass() {
        final Class<?> clazz = thriftMessageClassFinder.get();

        if (clazz == null) {
            throw new RuntimeException("Must call (indirectly) from a TBase/TApplicationException object.");
        }

        return clazz;
    }

    /**
     * Compute a new field name map for the current thrift message
     * we are parsing.
     */
    private <T extends TBase<T, F>, F extends TFieldIdEnum> Map<String, TField> computeFieldNameMap(
            Class<?> clazz) {
        final Map<String, TField> map = new HashMap<>();

        if (isTBase(clazz)) {
            // Get the metaDataMap for this Thrift class
            @SuppressWarnings("unchecked")
            final Map<? extends TFieldIdEnum, FieldMetaData> metaDataMap =
                    ThriftMetadataAccess.getStructMetaDataMap((Class<T>) clazz);

            for (Entry<? extends TFieldIdEnum, FieldMetaData> e : metaDataMap.entrySet()) {
                final String fieldName = e.getKey().getFieldName();
                final FieldMetaData metaData = e.getValue();
                updateClassMap(metaData, clazz);

                // Workaround a bug in the generated thrift message read()
                // method by mapping the ENUM type to the INT32 type
                // The thrift generated parsing code requires that, when expecting
                // a value of enum, we actually parse a value of type int32. The
                // generated read() method then looks up the enum value in a map.
                final byte type = TType.ENUM == metaData.valueMetaData.type ? TType.I32
                                                                            : metaData.valueMetaData.type;

                map.put(fieldName,
                        new TField(fieldName,
                                   type,
                                   e.getKey().getThriftFieldId()));
            }
        } else { // TApplicationException
            map.put("message", new TField("message", (byte)11, (short)1));
            map.put("type", new TField("type", (byte)8, (short)2));
        }

        return map;
    }

    private void updateClassMap(FieldMetaData metaData, Class<?> clazz) {
        final String fieldName = metaData.fieldName;

        final FieldValueMetaData elementMetaData;
        if (metaData.valueMetaData.isContainer()) {
            if (metaData.valueMetaData instanceof SetMetaData) {
                elementMetaData = ((SetMetaData) metaData.valueMetaData).elemMetaData;
            } else if (metaData.valueMetaData instanceof ListMetaData) {
                elementMetaData = ((ListMetaData) metaData.valueMetaData).elemMetaData;
            } else if (metaData.valueMetaData instanceof MapMetaData) {
                final MapMetaData mapMetaData = (MapMetaData) metaData.valueMetaData;
                final byte req = TFieldRequirementType.REQUIRED;

                final FieldMetaData keyMetaData = new FieldMetaData(
                        fieldName + TTextProtocol.MAP_KEY_SUFFIX, req, mapMetaData.keyMetaData);
                updateClassMap(keyMetaData, clazz);

                final FieldMetaData valueMetaData = new FieldMetaData(
                        fieldName + TTextProtocol.MAP_VALUE_SUFFIX, req, mapMetaData.valueMetaData);
                updateClassMap(valueMetaData, clazz);

                return;
            } else {
                // Unrecognized container type, but let's still continue processing without
                // special enum support.
                elementMetaData = metaData.valueMetaData;
            }
        } else {
            elementMetaData = metaData.valueMetaData;
        }

        if (elementMetaData instanceof EnumMetaData) {
            classMap.put(fieldName, ((EnumMetaData) elementMetaData).enumClass);
        } else if (elementMetaData instanceof StructMetaData) {
            classMap.put(fieldName, ((StructMetaData) elementMetaData).structClass);
        } else {
            // Workaround a bug where the generated 'FieldMetaData' does not provide
            // a fully qualified class name.
            final String typedefName = elementMetaData.getTypedefName();
            if (typedefName != null) {
                final String fqcn = clazz.getPackage().getName() + '.' + typedefName;
                Class<?> fieldClass = fieldMetaDataClassCache.get(fqcn);
                if (fieldClass == null) {
                    fieldClass = fieldMetaDataClassCache.computeIfAbsent(fqcn, key -> {
                        try {
                            return Class.forName(key);
                        } catch (ClassNotFoundException ignored) {
                            return StructContext.class;
                        }
                    });
                }
                if (fieldClass != StructContext.class) {
                    classMap.put(fieldName, fieldClass);
                }
            }
        }
    }
}
