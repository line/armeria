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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.thrift.TBase;
import org.apache.thrift.TEnum;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.meta_data.FieldValueMetaData;
import org.apache.thrift.meta_data.ListMetaData;
import org.apache.thrift.meta_data.MapMetaData;
import org.apache.thrift.meta_data.SetMetaData;
import org.apache.thrift.meta_data.StructMetaData;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TType;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.logging.FieldMasker;
import com.linecorp.armeria.internal.common.thrift.ThriftMetadataAccess;

final class UnMaskingContexts {

    interface MetadataContext {

        MetadataContext resolve();

        FieldValueMetaData valueMetaData();
    }

    static final class LostMetadataContext implements MetadataContext {

        static final MetadataContext INSTANCE = new LostMetadataContext();

        private LostMetadataContext() {}

        @Override
        public MetadataContext resolve() {
            return this;
        }

        @Override
        public FieldValueMetaData valueMetaData() {
            throw new UnsupportedOperationException();
        }
    }

    interface MaskingMetadataContext extends MetadataContext {
        FieldMasker masker();
    }

    static class PojoMetadataContext implements MaskingMetadataContext {

        private final FieldValueMetaData metaData;
        private final FieldMasker masker;

        PojoMetadataContext(FieldValueMetaData metaData, FieldMasker masker) {
            this.metaData = metaData;
            this.masker = masker;
        }

        @Override
        public FieldMasker masker() {
            return masker;
        }

        @Override
        public MetadataContext resolve() {
            if (metaData instanceof StructMetaData) {
                return new StructMetadataContext((StructMetaData) metaData, masker);
            }
            if (metaData instanceof SetMetaData) {
                return new CollectionMetadataContext((SetMetaData) metaData, masker);
            }
            if (metaData instanceof ListMetaData) {
                return new CollectionMetadataContext((ListMetaData) metaData, masker);
            }
            if (metaData instanceof MapMetaData) {
                return new CollectionMetadataContext((MapMetaData) metaData, masker);
            }
            return this;
        }

        @Override
        public FieldValueMetaData valueMetaData() {
            return metaData;
        }
    }

    static class CollectionMetadataContext implements MaskingMetadataContext {

        private final FieldValueMetaData metaData;
        private final FieldMasker masker;
        private final List<MaskingMetadataContext> childContexts;
        private int index;

        CollectionMetadataContext(SetMetaData metaData, FieldMasker masker) {
            this.metaData = metaData;
            this.masker = masker;
            childContexts = ImmutableList.of(new PojoMetadataContext(metaData.elemMetaData, masker));
        }

        CollectionMetadataContext(ListMetaData metaData, FieldMasker masker) {
            this.metaData = metaData;
            this.masker = masker;
            childContexts = ImmutableList.of(new PojoMetadataContext(metaData.elemMetaData, masker));
        }

        CollectionMetadataContext(MapMetaData metaData, FieldMasker masker) {
            this.metaData = metaData;
            this.masker = masker;
            childContexts = ImmutableList.of(new PojoMetadataContext(metaData.keyMetaData, masker),
                                             new PojoMetadataContext(metaData.valueMetaData, masker));
        }

        @Override
        public FieldMasker masker() {
            return masker;
        }

        @Override
        public MetadataContext resolve() {
            final MetadataContext context = childContexts.get(index % childContexts.size()).resolve();
            index++;
            return context;
        }

        @Override
        public FieldValueMetaData valueMetaData() {
            return metaData;
        }
    }

    static class StructMetadataContext implements MaskingMetadataContext {

        private final FieldMasker masker;
        private final StructMetaData valueMetadata;

        private final TBase defaultTbase;
        private final Map<? extends TFieldIdEnum, FieldMetaData> metadataMap;

        StructMetadataContext(StructMetaData valueMetadata, FieldMasker masker) {
            this.valueMetadata = valueMetadata;
            this.masker = masker;
            defaultTbase = TBaseCache.INSTANCE.newInstance(valueMetadata.structClass);
            metadataMap = ThriftMetadataAccess.getStructMetaDataMap(valueMetadata.structClass);
        }

        TFieldIdEnum tFieldIdEnum(TField tField) {
            return requireNonNull(defaultTbase.fieldForId(tField.id), "Unknown field :" + tField);
        }

        FieldMetaData fieldMetaData(TField tField) {
            return requireNonNull(metadataMap.get(tFieldIdEnum(tField)), "Unknown field :" + tField);
        }

        @Override
        public FieldMasker masker() {
            return masker;
        }

        @Override
        public MetadataContext resolve() {
            return this;
        }

        @Override
        public StructMetaData valueMetaData() {
            return valueMetadata;
        }
    }

    interface OverwriteMetadataContext extends MetadataContext {

        Object getObj();

        static OverwriteMetadataContext of(Object obj, FieldValueMetaData valueMetaData) {
            if (obj instanceof TBase) {
                return new TBaseOverwriteMetadataContext((TBase) obj, valueMetaData);
            }
            return new PojoOverwriteMetadataContext(obj, valueMetaData);
        }
    }

    static final class IgnoreOverwriteMetadataContext implements OverwriteMetadataContext {

        static final IgnoreOverwriteMetadataContext INSTANCE = new IgnoreOverwriteMetadataContext();

        private IgnoreOverwriteMetadataContext() {}

        @Override
        public MetadataContext resolve() {
            return this;
        }

        @Override
        public FieldValueMetaData valueMetaData() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getObj() {
            throw new UnsupportedOperationException();
        }
    }

    static class PojoOverwriteMetadataContext implements OverwriteMetadataContext {

        private final Object obj;
        private final FieldValueMetaData valueMetaData;

        PojoOverwriteMetadataContext(Object obj, FieldValueMetaData valueMetaData) {
            this.obj = obj;
            this.valueMetaData = valueMetaData;
        }

        @Override
        public Object getObj() {
            if (obj instanceof TEnum) {
                return ((TEnum) obj).getValue();
            }
            return obj;
        }

        @Override
        public MetadataContext resolve() {
            return this;
        }

        @Override
        public FieldValueMetaData valueMetaData() {
            return valueMetaData;
        }
    }

    static class TBaseOverwriteMetadataContext implements OverwriteMetadataContext {

        private final TBase<?, ?> tBase;
        private final FieldValueMetaData valueMetaData;
        private final List<FieldOverwriteMetadataContext> contexts;
        private int index;

        TBaseOverwriteMetadataContext(TBase<?, ?> tBase, FieldValueMetaData valueMetaData) {
            this.tBase = tBase;
            this.valueMetaData = valueMetaData;
            contexts = getContexts(tBase);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static List<FieldOverwriteMetadataContext> getContexts(TBase tBase) {
            final Map<? extends TFieldIdEnum, FieldMetaData> metadataMap =
                    ThriftMetadataAccess.getStructMetaDataMap(tBase.getClass());
            final ImmutableList.Builder<FieldOverwriteMetadataContext> contextsBuilder =
                    ImmutableList.builder();
            for (Entry<? extends TFieldIdEnum, FieldMetaData> e : metadataMap.entrySet()) {
                if (!tBase.isSet(e.getKey())) {
                    continue;
                }
                final Object obj = tBase.getFieldValue(e.getKey());
                contextsBuilder.add(new FieldOverwriteMetadataContext(obj, e.getKey(), e.getValue()));
            }
            return contextsBuilder.build();
        }

        @Override
        public FieldOverwriteMetadataContext resolve() {
            return contexts.get(index++);
        }

        @Override
        public TBase<?, ?> getObj() {
            return tBase;
        }

        @Override
        public FieldValueMetaData valueMetaData() {
            return valueMetaData;
        }

        boolean isDone() {
            return index >= contexts.size();
        }
    }

    static class MultiOverrideMetadataContext implements OverwriteMetadataContext {

        private final Object obj;
        private final FieldValueMetaData metaData;
        private final List<OverwriteMetadataContext> childContexts;
        private int index;

        MultiOverrideMetadataContext(Set<?> s, SetMetaData setMetaData) {
            obj = s;
            metaData = setMetaData;
            final ImmutableList.Builder<OverwriteMetadataContext> childContextsBuilder =
                    ImmutableList.builder();
            for (Object o : s) {
                childContextsBuilder.add(OverwriteMetadataContext.of(o, setMetaData));
            }
            childContexts = childContextsBuilder.build();
        }

        MultiOverrideMetadataContext(List<?> l, ListMetaData listMetaData) {
            obj = l;
            metaData = listMetaData;
            final ImmutableList.Builder<OverwriteMetadataContext> childContextsBuilder =
                    ImmutableList.builder();
            for (Object o : l) {
                childContextsBuilder.add(OverwriteMetadataContext.of(o, listMetaData));
            }
            childContexts = childContextsBuilder.build();
        }

        MultiOverrideMetadataContext(Map<?, ?> m, MapMetaData mapMetaData) {
            obj = m;
            metaData = mapMetaData;
            final ImmutableList.Builder<OverwriteMetadataContext> childContextsBuilder =
                    ImmutableList.builder();
            for (Entry<?, ?> o : m.entrySet()) {
                childContextsBuilder.add(OverwriteMetadataContext.of(o.getKey(),
                                                                     mapMetaData.keyMetaData));
                childContextsBuilder.add(OverwriteMetadataContext.of(o.getValue(),
                                                                     mapMetaData.valueMetaData));
            }
            childContexts = childContextsBuilder.build();
        }

        @Override
        public Object getObj() {
            return obj;
        }

        @Override
        public MetadataContext resolve() {
            return childContexts.get(index++);
        }

        @Override
        public FieldValueMetaData valueMetaData() {
            return metaData;
        }

        boolean done() {
            return index == childContexts.size();
        }
    }

    static class FieldOverwriteMetadataContext implements OverwriteMetadataContext {

        private final Object obj;
        private final TFieldIdEnum tFieldIdEnum;
        private final FieldMetaData fieldMetaData;

        FieldOverwriteMetadataContext(Object obj, TFieldIdEnum tFieldIdEnum, FieldMetaData fieldMetaData) {
            this.obj = obj;
            this.tFieldIdEnum = tFieldIdEnum;
            this.fieldMetaData = fieldMetaData;
        }

        TField tField() {
            if (obj instanceof TEnum) {
                // enums are serialized as integers
                return new TField(tFieldIdEnum.getFieldName(), TType.I32,
                                  tFieldIdEnum.getThriftFieldId());
            }
            return new TField(tFieldIdEnum.getFieldName(), fieldMetaData.valueMetaData.type,
                              tFieldIdEnum.getThriftFieldId());
        }

        @Override
        public Object getObj() {
            if (obj instanceof TEnum) {
                return ((TEnum) obj).getValue();
            }
            return obj;
        }

        @Override
        public MetadataContext resolve() {
            return this;
        }

        @Override
        public FieldValueMetaData valueMetaData() {
            return fieldMetaData.valueMetaData;
        }
    }

    private UnMaskingContexts() {}
}
