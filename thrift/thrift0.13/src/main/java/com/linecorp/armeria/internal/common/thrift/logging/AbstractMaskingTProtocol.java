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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.lenientFormat;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TList;
import org.apache.thrift.protocol.TMap;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TSet;
import org.apache.thrift.protocol.TStruct;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.logging.FieldMasker;
import com.linecorp.armeria.internal.common.thrift.ThriftMetadataAccess;

/**
 * A {@link TProtocol} that applies mapping except for some quirks.
 *
 * <ul>
 *     <li>
 *         As elements of Collections do not have an associated thrift metadata, they are
 *         not candidates for masking and hence {@link FieldMasker#mask(Object)} is not called on them.
 *     </li>
 *     <li>
 *         It is not practical nor reliable to allow users to implement collection masking.
 *         Only nullifying a collection is allowed for convenience.
 *     </li>
 * </ul>
 */
abstract class AbstractMaskingTProtocol extends TProtocol {

    private static final ByteBuffer EMPTY = ByteBuffer.wrap(new byte[] {});

    public int getMinSerializedSize(byte type) throws TException {
        return 0;
    }

    private final TProtocol delegate;
    private final Deque<Context> stack = new ArrayDeque<>();
    private final UnMaskingContextFactory contextFactory;

    AbstractMaskingTProtocol(TProtocol delegate, TBase<?, ?> base, TBaseSelectorCache selectorCache) {
        super(delegate.getTransport());
        this.delegate = delegate;
        contextFactory = new UnMaskingContextFactory(selectorCache);
        stack.push(new TBaseMaskingContext(base, FieldMasker.noMask(), 0));
    }

    TProtocol delegate() {
        return delegate;
    }

    @Override
    public void writeMessageBegin(TMessage tMessage) throws TException {
        delegate.writeMessageBegin(tMessage);
    }

    @Override
    public void writeMessageEnd() throws TException {
        delegate.writeMessageEnd();
    }

    @Override
    public void writeStructBegin(TStruct tStruct) throws TException {
        // write IgnoreFixedContext or TBaseMaskingContext
        final Context context = stack.getFirst().resolve();
        if (context instanceof IgnoreContext) {
            stack.push(IgnoreContext.INSTANCE);
            return;
        }
        assert context instanceof TBaseMaskingContext;
        final TBaseMaskingContext tBaseContext = (TBaseMaskingContext) context;
        final TBase<?, ?> tBase = tBaseContext.getObj();
        final Object masked = tBaseContext.masker().mask(tBase);
        if (masked == null) {
            delegate.writeStructBegin(tStruct);
            delegate.writeStructEnd();
            stack.push(IgnoreContext.INSTANCE);
            return;
        }
        final Class<?> mappedClass = tBaseContext.masker().mappedClass(tBase.getClass());
        checkArgument(masked.getClass() == mappedClass,
                      "Masked class <%s> does not match the expected mapped class <%s>",
                      masked.getClass(), mappedClass);
        checkArgument(mappedClass == String.class || mappedClass == tBase.getClass(),
                      "The mapped class <%s> can only be a 'String' or the original class <%s>",
                      mappedClass, tBase.getClass());
        if (masked instanceof String) {
            delegate.writeString((String) masked);
            stack.push(IgnoreContext.INSTANCE);
            return;
        }
        if (masked != tBase) {
            final TBase<?, ?> maskedTBase = (TBase<?, ?>) masked;
            // push a context with noMask to ensure that masking isn't done recursively
            stack.push(new TBaseMaskingContext(maskedTBase, FieldMasker.noMask(), stack.size()));
            maskedTBase.write(this);
            stack.pop();
            stack.push(IgnoreContext.INSTANCE);
            return;
        }
        delegate.writeStructBegin(tStruct);
        stack.push(tBaseContext.withDepth(stack.size()));
    }

    @Override
    public void writeStructEnd() throws TException {
        final Context context = stack.pop();
        if (context instanceof IgnoreContext) {
            return;
        }
        checkState(context.depth() == stack.size(), "Depth for context <%s> does not match the stack <%s>",
                   context, stack);
        delegate.writeStructEnd();
    }

    @Override
    public void writeFieldBegin(TField tField) throws TException {
        final Context context = stack.getFirst().resolve();
        if (context instanceof IgnoreContext) {
            stack.push(IgnoreContext.INSTANCE);
            return;
        }
        assert context instanceof TBaseMaskingContext;
        final TBaseMaskingContext tBaseContext = (TBaseMaskingContext) context;
        final Map<? extends TFieldIdEnum, FieldMetaData> metadataMap = tBaseContext.metadataMap();
        final TFieldIdEnum tFieldIdEnum = tBaseContext.getObj().fieldForId(tField.id);
        final FieldMetaData fieldMetaData = metadataMap.get(tFieldIdEnum);
        checkArgument(fieldMetaData != null, "Field <%s> does not exist for <%s>", tFieldIdEnum, tBaseContext);
        @SuppressWarnings("unchecked")
        final Object origValue = tBaseContext.getObj().getFieldValue(tFieldIdEnum);
        checkArgument(origValue != null, "Trying to write field <%s> which is 'null' for <%s>",
                      tFieldIdEnum, tBaseContext);
        final FieldMasker mapper = contextFactory.fieldMasker(tFieldIdEnum, fieldMetaData);
        stack.push(MaskingContext.of(origValue, mapper, stack.size()));
        delegate.writeFieldBegin(tField);
    }

    @Override
    public void writeFieldEnd() throws TException {
        final Context context = stack.pop();
        if (context instanceof IgnoreContext) {
            return;
        }
        checkState(context.depth() == stack.size(), "Depth for context <%s> does not match the stack <%s>",
                   context, stack);
        delegate.writeFieldEnd();
    }

    @Override
    public void writeFieldStop() throws TException {
        delegate.writeFieldStop();
    }

    @Override
    public void writeMapBegin(TMap tMap) throws TException {
        final Context context = stack.getFirst().resolve();
        if (context instanceof IgnoreContext) {
            stack.push(IgnoreContext.INSTANCE);
            return;
        }
        assert context instanceof PojoMaskingContext;
        final PojoMaskingContext pojoMaskingContext = (PojoMaskingContext) context;
        final Object obj = pojoMaskingContext.getObj();
        assert obj instanceof Map;
        final Map<Object, Object> origMap = Collections.unmodifiableMap((Map<?, ?>) obj);
        final Object maskedMap = pojoMaskingContext.masker().mask(origMap);
        if (maskedMap == null) {
            // write an empty map
            delegate.writeMapBegin(new TMap(tMap.keyType, tMap.valueType, 0));
            delegate.writeMapEnd();
            stack.push(IgnoreContext.INSTANCE);
            return;
        }
        // masking to another map is not allowed since writing the map ourselves is not reliable
        checkArgument(maskedMap == origMap,
                      "Masking to map <%s> is not allowed. Use one of 'FieldMasker.nullify()'," +
                      " 'FieldMasker.noMask()', or 'FieldMasker.fallthrough()' instead.", maskedMap);
        delegate.writeMapBegin(tMap);
        stack.push(new MultiMaskingContext(origMap, pojoMaskingContext.masker(), stack.size()));
    }

    @Override
    public void writeMapEnd() throws TException {
        final Context context = stack.pop();
        if (context instanceof IgnoreContext) {
            return;
        }
        delegate.writeMapEnd();
        assert context instanceof MultiMaskingContext;
        final MultiMaskingContext multiMaskingContext = (MultiMaskingContext) context;
        checkState(multiMaskingContext.depth() == stack.size(),
                   "Depth for context <%s> does not match the stack <%s>", context, stack);
        checkState(multiMaskingContext.done(), "Context <%s> is not fully consumed with stack: <%s>",
                   context, stack);
    }

    @Override
    public void writeListBegin(TList tList) throws TException {
        final Context context = stack.getFirst().resolve();
        if (context instanceof IgnoreContext) {
            stack.push(IgnoreContext.INSTANCE);
            return;
        }
        assert context instanceof PojoMaskingContext;
        final PojoMaskingContext pojoMaskingContext = (PojoMaskingContext) context;
        final Object obj = context.getObj();
        assert obj instanceof List;
        final List<Object> origList = Collections.unmodifiableList((List<?>) obj);
        final Object maskedList = pojoMaskingContext.masker().mask(origList);
        if (maskedList == null) {
            delegate.writeListBegin(new TList(tList.elemType, 0));
            delegate.writeListEnd();
            stack.push(IgnoreContext.INSTANCE);
            return;
        }
        // masking to another list is not allowed since writing the list ourselves is not reliable
        checkArgument(maskedList == origList,
                      "Masking to list <%s> is not allowed. Use one of 'FieldMasker.nullify()'," +
                      " 'FieldMasker.noMask()', or 'FieldMasker.fallthrough()' instead.", maskedList);
        delegate.writeListBegin(tList);
        final MultiMaskingContext childContext =
                new MultiMaskingContext(origList, pojoMaskingContext.masker(), stack.size());
        stack.push(childContext);
    }

    @Override
    public void writeListEnd() throws TException {
        final Context context = stack.pop();
        if (context instanceof IgnoreContext) {
            return;
        }
        delegate.writeListEnd();
        assert context instanceof MultiMaskingContext;
        final MultiMaskingContext multiMaskingContext = (MultiMaskingContext) context;
        checkState(multiMaskingContext.depth() == stack.size(),
                   "Depth for context <%s> does not match the stack <%s>", context, stack);
        checkState(multiMaskingContext.done(), "Context <%s> is not fully consumed with stack: <%s>",
                   context, stack);
    }

    @Override
    public void writeSetBegin(TSet tSet) throws TException {
        final Context context = stack.getFirst().resolve();
        if (context instanceof IgnoreContext) {
            stack.push(IgnoreContext.INSTANCE);
            return;
        }
        assert context instanceof PojoMaskingContext;
        final PojoMaskingContext pojoMaskingContext = (PojoMaskingContext) context;
        final Object obj = pojoMaskingContext.getObj();
        assert obj instanceof Set;
        final Set<Object> origSet = Collections.unmodifiableSet((Set<?>) obj);
        final Object maskedSet = pojoMaskingContext.masker().mask(origSet);
        if (maskedSet == null) {
            delegate.writeSetBegin(new TSet(tSet.elemType, 0));
            delegate.writeSetEnd();
            stack.push(IgnoreContext.INSTANCE);
            return;
        }
        // masking to another set is not allowed since writing the set ourselves is not reliable
        checkArgument(maskedSet == origSet,
                      "Masking to set <%s> is not allowed. Use one of 'FieldMasker.nullify()'," +
                      " 'FieldMasker.noMask()', or 'FieldMasker.fallthrough()' instead.", maskedSet);
        delegate.writeSetBegin(tSet);
        stack.push(new MultiMaskingContext(origSet, pojoMaskingContext.masker(), stack.size()));
    }

    @Override
    public void writeSetEnd() throws TException {
        final Context context = stack.pop();
        if (context instanceof IgnoreContext) {
            return;
        }
        delegate.writeSetEnd();
        assert context instanceof MultiMaskingContext;
        final MultiMaskingContext multiMaskingContext = (MultiMaskingContext) context;
        checkState(multiMaskingContext.depth() == stack.size(),
                   "Depth for context <%s> does not match the stack <%s>", context, stack);
        checkState(multiMaskingContext.done(), "Context <%s> is not fully consumed with stack: <%s>",
                   context, stack);
    }

    @Override
    public void writeBool(boolean b) throws TException {
        final Context context = stack.getFirst().resolve();
        if (context instanceof IgnoreContext) {
            return;
        }
        assert context instanceof PojoMaskingContext;
        final PojoMaskingContext pojoMaskingContext = (PojoMaskingContext) context;
        maybeMask(b, Boolean.FALSE, pojoMaskingContext, delegate::writeBool, delegate);
    }

    @Override
    public void writeByte(byte b) throws TException {
        final Context context = stack.getFirst().resolve();
        if (context instanceof IgnoreContext) {
            return;
        }
        assert context instanceof PojoMaskingContext;
        final PojoMaskingContext pojoMaskingContext = (PojoMaskingContext) context;
        maybeMask(b, (byte) 0, pojoMaskingContext, delegate::writeByte, delegate);
    }

    @Override
    public void writeI16(short i) throws TException {
        final Context context = stack.getFirst().resolve();
        if (context instanceof IgnoreContext) {
            return;
        }
        assert context instanceof PojoMaskingContext;
        final PojoMaskingContext pojoMaskingContext = (PojoMaskingContext) context;
        maybeMask(i, (short) 0, pojoMaskingContext, delegate::writeI16, delegate);
    }

    @Override
    public void writeI32(int i) throws TException {
        final Context context = stack.getFirst().resolve();
        if (context instanceof IgnoreContext) {
            return;
        }
        assert context instanceof PojoMaskingContext;
        final PojoMaskingContext pojoMaskingContext = (PojoMaskingContext) context;
        maybeMask(i, 0, pojoMaskingContext, delegate::writeI32, delegate);
    }

    @Override
    public void writeI64(long l) throws TException {
        final Context context = stack.getFirst().resolve();
        if (context instanceof IgnoreContext) {
            return;
        }
        assert context instanceof PojoMaskingContext;
        final PojoMaskingContext pojoMaskingContext = (PojoMaskingContext) context;
        maybeMask(l, 0L, pojoMaskingContext, delegate::writeI64, delegate);
    }

    @Override
    public void writeDouble(double v) throws TException {
        final Context context = stack.getFirst().resolve();
        if (context instanceof IgnoreContext) {
            return;
        }
        assert context instanceof PojoMaskingContext;
        final PojoMaskingContext pojoMaskingContext = (PojoMaskingContext) context;
        maybeMask(v, 0d, pojoMaskingContext, delegate::writeDouble, delegate);
    }

    @Override
    public void writeString(String s) throws TException {
        final Context context = stack.getFirst().resolve();
        if (context instanceof IgnoreContext) {
            return;
        }
        assert context instanceof PojoMaskingContext;
        final PojoMaskingContext pojoMaskingContext = (PojoMaskingContext) context;
        maybeMask(s, "", pojoMaskingContext, delegate::writeString, delegate);
    }

    @Override
    public void writeBinary(ByteBuffer byteBuffer) throws TException {
        final Context context = stack.getFirst().resolve();
        if (context instanceof IgnoreContext) {
            return;
        }
        assert context instanceof PojoMaskingContext;
        final PojoMaskingContext pojoMaskingContext = (PojoMaskingContext) context;
        maybeMask(byteBuffer, EMPTY, pojoMaskingContext, delegate::writeBinary, delegate);
    }

    private interface ThrowingConsumer<T> {
        void accept(T t) throws TException;
    }

    @SuppressWarnings("unchecked")
    static <T> void maybeMask(T obj, T defaultValue, MaskingContext context,
                              ThrowingConsumer<T> writer, TProtocol delegate) throws TException {
        final Object maybeMasked = context.masker().mask(obj);
        if (maybeMasked == null) {
            writer.accept(defaultValue);
            return;
        }
        if (obj.getClass() == maybeMasked.getClass()) {
            writer.accept((T) maybeMasked);
            return;
        }
        if (maybeMasked.getClass() == String.class) {
            delegate.writeString((String) maybeMasked);
            return;
        }
        throw new IllegalArgumentException(lenientFormat(
                "The masked class <%s> should match the original class <%s> or be a <String>",
                maybeMasked.getClass(), obj.getClass()));
    }

    interface Context {
        Context resolve();

        Object getObj();

        int depth();
    }

    private interface MaskingContext extends Context {

        FieldMasker masker();

        static MaskingContext of(Object obj, FieldMasker masker, int depth) {
            if (obj instanceof TBase) {
                return new TBaseMaskingContext((TBase<?, ?>) obj, masker, depth);
            }
            return new PojoMaskingContext(obj, masker, depth);
        }
    }

    private static class IgnoreContext implements Context {
        private static final IgnoreContext INSTANCE = new IgnoreContext();

        @Override
        public Context resolve() {
            return this;
        }

        @Override
        public Object getObj() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int depth() {
            return -1;
        }
    }

    private static class TBaseMaskingContext implements MaskingContext {

        private final TBase<?, ?> tBase;
        private final FieldMasker masker;
        private final int depth;
        private final Map<? extends TFieldIdEnum, FieldMetaData> metadataMap;

        TBaseMaskingContext(TBase<?, ?> tBase, FieldMasker masker, int depth) {
            this.tBase = tBase;
            this.masker = masker;
            this.depth = depth;
            metadataMap = ThriftMetadataAccess.getStructMetaDataMap(tBase.getClass());
        }

        @Override
        public Context resolve() {
            return this;
        }

        @SuppressWarnings("rawtypes")
        @Override
        public TBase getObj() {
            return tBase;
        }

        @Override
        public int depth() {
            return depth;
        }

        @Override
        public FieldMasker masker() {
            return masker;
        }

        Map<? extends TFieldIdEnum, FieldMetaData> metadataMap() {
            return metadataMap;
        }

        TBaseMaskingContext withDepth(int depth) {
            return new TBaseMaskingContext(tBase, masker, depth);
        }
    }

    static class PojoMaskingContext implements MaskingContext {

        private final Object obj;
        private final FieldMasker masker;
        private final int depth;

        PojoMaskingContext(Object obj, FieldMasker masker, int depth) {
            this.obj = obj;
            this.masker = masker;
            this.depth = depth;
        }

        @Override
        public Context resolve() {
            return this;
        }

        @Override
        public Object getObj() {
            return obj;
        }

        @Override
        public int depth() {
            return depth;
        }

        @Override
        public FieldMasker masker() {
            return masker;
        }
    }

    private static class MultiMaskingContext implements MaskingContext {
        private final FieldMasker masker;
        private final int depth;
        private int index;
        private final List<MaskingContext> contexts;

        MultiMaskingContext(Collection<Object> objs, FieldMasker masker, int depth) {
            this.masker = masker;
            this.depth = depth;
            final ImmutableList.Builder<MaskingContext> builder = ImmutableList.builder();
            for (Object o : objs) {
                builder.add(MaskingContext.of(o, masker, depth));
            }
            contexts = builder.build();
        }

        MultiMaskingContext(Map<Object, Object> m, FieldMasker masker, int depth) {
            this.masker = masker;
            this.depth = depth;
            final ImmutableList.Builder<MaskingContext> builder = ImmutableList.builder();
            for (Entry<Object, Object> o : m.entrySet()) {
                builder.add(MaskingContext.of(o.getKey(), masker, depth));
                builder.add(MaskingContext.of(o.getValue(), masker, depth));
            }
            contexts = builder.build();
        }

        @Override
        public FieldMasker masker() {
            return masker;
        }

        @Override
        public Context resolve() {
            return contexts.get(index++);
        }

        @Override
        public Object getObj() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int depth() {
            return depth;
        }

        boolean done() {
            return index == contexts.size();
        }
    }
}
