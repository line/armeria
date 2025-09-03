/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.internal.common.thrift.logging;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.lenientFormat;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.meta_data.FieldValueMetaData;
import org.apache.thrift.meta_data.ListMetaData;
import org.apache.thrift.meta_data.MapMetaData;
import org.apache.thrift.meta_data.SetMetaData;
import org.apache.thrift.meta_data.StructMetaData;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TList;
import org.apache.thrift.protocol.TMap;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.protocol.TSet;
import org.apache.thrift.protocol.TStruct;
import org.apache.thrift.protocol.TType;

import com.linecorp.armeria.common.logging.FieldMasker;
import com.linecorp.armeria.internal.common.thrift.logging.UnMaskingContexts.CollectionMetadataContext;
import com.linecorp.armeria.internal.common.thrift.logging.UnMaskingContexts.FieldOverwriteMetadataContext;
import com.linecorp.armeria.internal.common.thrift.logging.UnMaskingContexts.IgnoreOverwriteMetadataContext;
import com.linecorp.armeria.internal.common.thrift.logging.UnMaskingContexts.LostMetadataContext;
import com.linecorp.armeria.internal.common.thrift.logging.UnMaskingContexts.MetadataContext;
import com.linecorp.armeria.internal.common.thrift.logging.UnMaskingContexts.MultiOverrideMetadataContext;
import com.linecorp.armeria.internal.common.thrift.logging.UnMaskingContexts.OverwriteMetadataContext;
import com.linecorp.armeria.internal.common.thrift.logging.UnMaskingContexts.PojoMetadataContext;
import com.linecorp.armeria.internal.common.thrift.logging.UnMaskingContexts.StructMetadataContext;
import com.linecorp.armeria.internal.common.thrift.logging.UnMaskingContexts.TBaseOverwriteMetadataContext;

abstract class AbstractUnMaskingTProtocol extends TProtocol {

    private static final TField STOP_FIELD = new TField();

    private final TProtocol delegate;
    private final Deque<MetadataContext> stack = new ArrayDeque<>();
    private final TBaseSelectorCache selectorCache;

    AbstractUnMaskingTProtocol(TProtocol delegate, TBase<?, ?> base, TBaseSelectorCache selectorCache) {
        super(delegate.getTransport());
        this.delegate = delegate;
        this.selectorCache = selectorCache;
        stack.push(new StructMetadataContext(new StructMetaData(TType.STRUCT, base.getClass()),
                                             FieldMasker.noMask()));
    }

    @Override
    public TMessage readMessageBegin() throws TException {
        return delegate.readMessageBegin();
    }

    @Override
    public void readMessageEnd() throws TException {
        delegate.readMessageEnd();
    }

    @Override
    public TStruct readStructBegin() throws TException {
        final MetadataContext context = stack.getFirst().resolve();
        if (context instanceof OverwriteMetadataContext) {
            final OverwriteMetadataContext overwriteContext = (OverwriteMetadataContext) context;
            if (overwriteContext.getObj() instanceof TBase) {
                stack.push(new TBaseOverwriteMetadataContext((TBase) overwriteContext.getObj(),
                                                             overwriteContext.valueMetaData()));
            } else {
                stack.push(IgnoreOverwriteMetadataContext.INSTANCE);
            }
            return new TStruct();
        }
        if (!(context instanceof StructMetadataContext)) {
            stack.push(LostMetadataContext.INSTANCE);
            return delegate.readStructBegin();
        }
        final StructMetadataContext metadataContext = (StructMetadataContext) context;
        final StructMetaData structMetaData = metadataContext.valueMetaData();
        final Class<?> aClass = structMetaData.structClass;
        final Class<?> mappedClass = metadataContext.masker().mappedClass(aClass);
        if (aClass == mappedClass) {
            stack.push(new StructMetadataContext(structMetaData, FieldMasker.noMask()));
            return delegate.readStructBegin();
        }
        if (String.class.isAssignableFrom(mappedClass)) {
            final String str = delegate.readString();
            final TBase unmasked = (TBase) metadataContext.masker().unmask(str, aClass);
            stack.push(new TBaseOverwriteMetadataContext(unmasked, metadataContext.valueMetaData()));
            return new TStruct();
        }
        throw new TProtocolException("Cannot unmask class '" + aClass.getSimpleName() + "' from '" +
                                     mappedClass.getSimpleName() + "'.");
    }

    @Override
    public void readStructEnd() throws TException {
        final MetadataContext context = stack.pop();
        if (context instanceof OverwriteMetadataContext) {
            return;
        }
        delegate.readStructEnd();
    }

    @Override
    public TField readFieldBegin() throws TException {
        final MetadataContext context = stack.getFirst();
        if (context instanceof OverwriteMetadataContext) {
            if (!(context instanceof TBaseOverwriteMetadataContext)) {
                return STOP_FIELD;
            }
            final TBaseOverwriteMetadataContext tbaseOverwriteContext = (TBaseOverwriteMetadataContext) context;
            if (tbaseOverwriteContext.isDone()) {
                return STOP_FIELD;
            }
            final FieldOverwriteMetadataContext overwriteMetadataContext = tbaseOverwriteContext.resolve();
            stack.push(overwriteMetadataContext);
            return overwriteMetadataContext.tField();
        }
        final TField tField = delegate.readFieldBegin();
        if (tField.type == TType.STOP) {
            return tField;
        }
        if (!(context instanceof StructMetadataContext)) {
            stack.push(LostMetadataContext.INSTANCE);
            return tField;
        }
        final StructMetadataContext maskingContext = (StructMetadataContext) context;
        final TFieldIdEnum tFieldIdEnum = maskingContext.tFieldIdEnum(tField);
        final FieldMetaData fieldMetaData = maskingContext.fieldMetaData(tField);
        final FieldMasker masker = selectorCache.getMapper(tFieldIdEnum, fieldMetaData);
        final MetadataContext childContext = new PojoMetadataContext(fieldMetaData.valueMetaData, masker);
        stack.push(childContext);
        return tField;
    }

    @Override
    public void readFieldEnd() throws TException {
        final MetadataContext context = stack.pop();
        if (context instanceof OverwriteMetadataContext) {
            return;
        }
        delegate.readFieldEnd();
    }

    @Override
    public TMap readMapBegin() throws TException {
        final MetadataContext context = stack.getFirst().resolve();
        if (context instanceof OverwriteMetadataContext) {
            final OverwriteMetadataContext overwriteContext = (OverwriteMetadataContext) context;
            final FieldValueMetaData metaData = overwriteContext.valueMetaData();
            final Object obj = overwriteContext.getObj();
            if (metaData instanceof MapMetaData && obj instanceof Map) {
                stack.push(new MultiOverrideMetadataContext((Map<?, ?>) obj, (MapMetaData) metaData
                ));
                return new TMap(((MapMetaData) metaData).keyMetaData.type,
                                ((MapMetaData) metaData).valueMetaData.type, ((Map<?, ?>) obj).size());
            }
            // the context has been lost
            stack.push(IgnoreOverwriteMetadataContext.INSTANCE);
            return new TMap();
        }
        final TMap tMap = delegate.readMapBegin();
        if (!(context instanceof CollectionMetadataContext)) {
            stack.push(LostMetadataContext.INSTANCE);
            return tMap;
        }
        final CollectionMetadataContext metadataContext = (CollectionMetadataContext) context;
        stack.push(metadataContext);
        return tMap;
    }

    @Override
    public void readMapEnd() throws TException {
        final MetadataContext context = stack.pop();
        if (context instanceof MultiOverrideMetadataContext) {
            checkState(((MultiOverrideMetadataContext) context).done(),
                       "Collection <%s> is not fully consumed with stack: <%s>",
                       context, stack);
        }
        if (context instanceof OverwriteMetadataContext) {
            return;
        }
        delegate.readMapEnd();
    }

    @Override
    public TList readListBegin() throws TException {
        final MetadataContext context = stack.getFirst().resolve();
        if (context instanceof OverwriteMetadataContext) {
            final OverwriteMetadataContext overwriteContext = (OverwriteMetadataContext) context;

            final FieldValueMetaData metaData = overwriteContext.valueMetaData();
            final Object obj = overwriteContext.getObj();
            if (metaData instanceof ListMetaData && obj instanceof List) {
                stack.push(new MultiOverrideMetadataContext((List<?>) obj, (ListMetaData) metaData));
                return new TList(((ListMetaData) metaData).elemMetaData.type, ((List<?>) obj).size());
            }
            // the context has been lost
            stack.push(IgnoreOverwriteMetadataContext.INSTANCE);
            return new TList();
        }
        final TList tList = delegate.readListBegin();
        if (!(context instanceof CollectionMetadataContext)) {
            stack.push(LostMetadataContext.INSTANCE);
            return tList;
        }
        final CollectionMetadataContext metadataContext = (CollectionMetadataContext) context;
        stack.push(metadataContext);
        return tList;
    }

    @Override
    public void readListEnd() throws TException {
        final MetadataContext context = stack.pop();
        if (context instanceof MultiOverrideMetadataContext) {
            checkState(((MultiOverrideMetadataContext) context).done(),
                       "Collection <%s> is not fully consumed with stack: <%s>",
                       context, stack);
        }
        if (context instanceof OverwriteMetadataContext) {
            return;
        }
        delegate.readListEnd();
    }

    @Override
    public TSet readSetBegin() throws TException {
        final MetadataContext context = stack.getFirst().resolve();
        if (context instanceof OverwriteMetadataContext) {
            final OverwriteMetadataContext overwriteContext = (OverwriteMetadataContext) context;
            final FieldValueMetaData metaData = overwriteContext.valueMetaData();
            final Object obj = overwriteContext.getObj();
            if (metaData instanceof SetMetaData && obj instanceof Set) {
                stack.push(new MultiOverrideMetadataContext((Set<?>) obj, (SetMetaData) metaData
                ));
                return new TSet(((SetMetaData) metaData).elemMetaData.type, ((Set<?>) obj).size());
            }
            // the context has been lost
            stack.push(IgnoreOverwriteMetadataContext.INSTANCE);
            return new TSet();
        }
        final TSet tSet = delegate.readSetBegin();
        if (!(context instanceof CollectionMetadataContext)) {
            stack.push(LostMetadataContext.INSTANCE);
            return tSet;
        }
        final CollectionMetadataContext metadataContext = (CollectionMetadataContext) context;
        stack.push(metadataContext);
        return tSet;
    }

    @Override
    public void readSetEnd() throws TException {
        final MetadataContext context = stack.pop();
        if (context instanceof MultiOverrideMetadataContext) {
            checkState(((MultiOverrideMetadataContext) context).done(),
                       "Collection <%s> is not fully consumed with stack: <%s>",
                       context, stack);
        }
        if (context instanceof OverwriteMetadataContext) {
            return;
        }
        delegate.readSetEnd();
    }

    @Override
    public boolean readBool() throws TException {
        final MetadataContext context = stack.getFirst().resolve();
        if (context instanceof OverwriteMetadataContext) {
            final OverwriteMetadataContext overwriteMetadataContext = (OverwriteMetadataContext) context;
            return (boolean) overwriteMetadataContext.getObj();
        }
        if (!(context instanceof PojoMetadataContext)) {
            return delegate.readBool();
        }
        final PojoMetadataContext metadataContext = (PojoMetadataContext) context;
        return tryUnmask(metadataContext, Boolean.class, delegate::readBool, delegate);
    }

    @Override
    public byte readByte() throws TException {
        final MetadataContext context = stack.getFirst().resolve();
        if (context instanceof OverwriteMetadataContext) {
            final OverwriteMetadataContext overwriteMetadataContext = (OverwriteMetadataContext) context;
            return (byte) overwriteMetadataContext.getObj();
        }
        if (!(context instanceof PojoMetadataContext)) {
            return delegate.readByte();
        }
        final PojoMetadataContext metadataContext = (PojoMetadataContext) context;
        return tryUnmask(metadataContext, Byte.class, delegate::readByte, delegate);
    }

    @Override
    public short readI16() throws TException {
        final MetadataContext context = stack.getFirst().resolve();
        if (context instanceof OverwriteMetadataContext) {
            final OverwriteMetadataContext overwriteMetadataContext = (OverwriteMetadataContext) context;
            return (short) overwriteMetadataContext.getObj();
        }
        if (!(context instanceof PojoMetadataContext)) {
            return delegate.readI16();
        }
        final PojoMetadataContext metadataContext = (PojoMetadataContext) context;
        return tryUnmask(metadataContext, Short.class, delegate::readI16, delegate);
    }

    @Override
    public int readI32() throws TException {
        final MetadataContext context = stack.getFirst().resolve();
        if (context instanceof OverwriteMetadataContext) {
            final OverwriteMetadataContext overwriteMetadataContext = (OverwriteMetadataContext) context;
            return (int) overwriteMetadataContext.getObj();
        }
        if (!(context instanceof PojoMetadataContext)) {
            return delegate.readI32();
        }
        final PojoMetadataContext metadataContext = (PojoMetadataContext) context;
        return tryUnmask(metadataContext, Integer.class, delegate::readI32, delegate);
    }

    @Override
    public long readI64() throws TException {
        final MetadataContext context = stack.getFirst().resolve();
        if (context instanceof OverwriteMetadataContext) {
            final OverwriteMetadataContext overwriteMetadataContext = (OverwriteMetadataContext) context;
            return (long) overwriteMetadataContext.getObj();
        }
        if (!(context instanceof PojoMetadataContext)) {
            return delegate.readI64();
        }
        final PojoMetadataContext metadataContext = (PojoMetadataContext) context;
        return tryUnmask(metadataContext, Long.class, delegate::readI64, delegate);
    }

    @Override
    public double readDouble() throws TException {
        final MetadataContext context = stack.getFirst().resolve();
        if (context instanceof OverwriteMetadataContext) {
            final OverwriteMetadataContext overwriteMetadataContext = (OverwriteMetadataContext) context;
            return (double) overwriteMetadataContext.getObj();
        }
        if (!(context instanceof PojoMetadataContext)) {
            return delegate.readDouble();
        }
        final PojoMetadataContext metadataContext = (PojoMetadataContext) context;
        return tryUnmask(metadataContext, Double.class, delegate::readDouble, delegate);
    }

    @Override
    public String readString() throws TException {
        final MetadataContext context = stack.getFirst().resolve();
        if (context instanceof OverwriteMetadataContext) {
            final OverwriteMetadataContext overwriteMetadataContext = (OverwriteMetadataContext) context;
            return (String) overwriteMetadataContext.getObj();
        }
        if (!(context instanceof PojoMetadataContext)) {
            return delegate.readString();
        }
        final PojoMetadataContext metadataContext = (PojoMetadataContext) context;
        return tryUnmask(metadataContext, String.class, delegate::readString, delegate);
    }

    @Override
    public ByteBuffer readBinary() throws TException {
        final MetadataContext context = stack.getFirst().resolve();
        if (context instanceof OverwriteMetadataContext) {
            final OverwriteMetadataContext overwriteMetadataContext = (OverwriteMetadataContext) context;
            return ByteBuffer.wrap((byte[]) overwriteMetadataContext.getObj());
        }
        if (!(context instanceof PojoMetadataContext)) {
            return delegate.readBinary();
        }
        final PojoMetadataContext metadataContext = (PojoMetadataContext) context;
        return tryUnmask(metadataContext, ByteBuffer.class, delegate::readBinary, delegate);
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws TException;
    }

    @SuppressWarnings("unchecked")
    static <T> T tryUnmask(PojoMetadataContext context, Class<T> expectedClass, ThrowingSupplier<T> reader,
                           TProtocol delegate) throws TException {
        final Class<?> mappedClass = context.masker().mappedClass(expectedClass);
        if (mappedClass == expectedClass) {
            final T readValue = reader.get();
            return (T) context.masker().unmask(readValue, expectedClass);
        }
        if (mappedClass == String.class) {
            final String masked = delegate.readString();
            return (T) context.masker().unmask(masked, expectedClass);
        }
        throw new IllegalArgumentException(lenientFormat(
                "The unmasked class <%s> should match the original class <%s> or be a <String>",
                mappedClass, expectedClass));
    }

    // Methods for backwards compatibility across thrift versions

    public int getMinSerializedSize(byte b) throws TException {
        return 0;
    }

    TProtocol delegate() {
        return delegate;
    }

    Deque<MetadataContext> stack() {
        return stack;
    }

    // Unsupported write operations from this point

    @Override
    public void writeMessageBegin(TMessage message) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeMessageEnd() throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeStructBegin(TStruct struct) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeStructEnd() throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeFieldBegin(TField field) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeFieldEnd() throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeFieldStop() throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeMapBegin(TMap map) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeMapEnd() throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeListBegin(TList list) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeListEnd() throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeSetBegin(TSet set) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeSetEnd() throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeBool(boolean b) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeByte(byte b) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeI16(short i16) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeI32(int i32) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeI64(long i64) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeDouble(double dub) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeString(String str) throws TException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeBinary(ByteBuffer buf) throws TException {
        throw new UnsupportedOperationException();
    }
}
