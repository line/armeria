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

final class UnMaskingContextFactory {

    private final TBaseSelectorCache tBaseSelectorCache;

    UnMaskingContextFactory(TBaseSelectorCache tBaseSelectorCache) {
        this.tBaseSelectorCache = tBaseSelectorCache;
    }

    FieldMasker fieldMasker(TFieldIdEnum tFieldIdEnum, FieldMetaData fieldMetaData) {
        return tBaseSelectorCache.getMapper(tFieldIdEnum, fieldMetaData);
    }

    interface MetadataContext {

        MetadataContext resolve();

        int depth();

        FieldValueMetaData valueMetaData();
    }

    static class LostMetadataContext implements MetadataContext {

        private final int depth;

        LostMetadataContext(int depth) {
            this.depth = depth;
        }

        @Override
        public MetadataContext resolve() {
            return this;
        }

        @Override
        public int depth() {
            return depth;
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
        private final int depth;

        PojoMetadataContext(FieldValueMetaData metaData, FieldMasker masker, int depth) {
            this.metaData = metaData;
            this.masker = masker;
            this.depth = depth;
        }

        @Override
        public FieldMasker masker() {
            return masker;
        }

        @Override
        public MetadataContext resolve() {
            if (metaData instanceof StructMetaData) {
                return new StructMetadataContext((StructMetaData) metaData, masker, depth);
            }
            if (metaData instanceof SetMetaData) {
                return new CollectionMetadataContext((SetMetaData) metaData, masker, depth);
            }
            if (metaData instanceof ListMetaData) {
                return new CollectionMetadataContext((ListMetaData) metaData, masker, depth);
            }
            if (metaData instanceof MapMetaData) {
                return new CollectionMetadataContext((MapMetaData) metaData, masker, depth);
            }
            return this;
        }

        @Override
        public int depth() {
            return depth;
        }

        @Override
        public FieldValueMetaData valueMetaData() {
            return metaData;
        }
    }

    static class CollectionMetadataContext implements MaskingMetadataContext {

        private final FieldValueMetaData metaData;
        private final FieldMasker masker;
        private final int depth;
        private final List<MaskingMetadataContext> childContexts;
        private int index;

        CollectionMetadataContext(SetMetaData metaData, FieldMasker masker, int depth) {
            this.metaData = metaData;
            this.masker = masker;
            this.depth = depth;
            childContexts = ImmutableList.of(new PojoMetadataContext(metaData.elemMetaData, masker, depth));
        }

        CollectionMetadataContext(ListMetaData metaData, FieldMasker masker, int depth) {
            this.metaData = metaData;
            this.masker = masker;
            this.depth = depth;
            childContexts = ImmutableList.of(new PojoMetadataContext(metaData.elemMetaData, masker, depth));
        }

        CollectionMetadataContext(MapMetaData metaData, FieldMasker masker, int depth) {
            this.metaData = metaData;
            this.masker = masker;
            this.depth = depth;
            childContexts = ImmutableList.of(new PojoMetadataContext(metaData.keyMetaData, masker, depth),
                                             new PojoMetadataContext(metaData.valueMetaData, masker, depth));
        }

        private static CollectionMetadataContext of(FieldValueMetaData metaData,
                                                    FieldMasker masker, int depth) {
            if (metaData instanceof SetMetaData) {
                return new CollectionMetadataContext((SetMetaData) metaData, masker, depth);
            } else if (metaData instanceof ListMetaData) {
                return new CollectionMetadataContext((ListMetaData) metaData, masker, depth);
            } else if (metaData instanceof MapMetaData) {
                return new CollectionMetadataContext((MapMetaData) metaData, masker, depth);
            } else {
                throw new IllegalArgumentException();
            }
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
        public int depth() {
            return depth;
        }

        @Override
        public FieldValueMetaData valueMetaData() {
            return metaData;
        }

        CollectionMetadataContext withDepth(int depth) {
            return CollectionMetadataContext.of(metaData, masker, depth);
        }
    }

    static class StructMetadataContext implements MaskingMetadataContext {

        private final FieldMasker masker;
        private final int depth;
        private final StructMetaData valueMetadata;

        private final TBase defaultTbase;
        private final Map<? extends TFieldIdEnum, FieldMetaData> metadataMap;

        StructMetadataContext(StructMetaData valueMetadata, FieldMasker masker, int depth) {
            this.valueMetadata = valueMetadata;
            this.masker = masker;
            this.depth = depth;
            defaultTbase = TBaseCache.INSTANCE.newInstance(valueMetadata.structClass);
            metadataMap = ThriftMetadataAccess.getStructMetaDataMap(valueMetadata.structClass);
        }

        TFieldIdEnum tFieldIdEnum(TField tField) {
            return requireNonNull(defaultTbase.fieldForId(tField.id));
        }

        FieldMetaData fieldMetaData(TField tField) {
            return requireNonNull(metadataMap.get(tFieldIdEnum(tField)));
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
        public int depth() {
            return depth;
        }

        @Override
        public StructMetaData valueMetaData() {
            return valueMetadata;
        }
    }

    interface OverwriteMetadataContext extends MetadataContext {

        Object getObj();

        static OverwriteMetadataContext of(Object obj, int depth, FieldValueMetaData valueMetaData) {
            if (obj instanceof TBase) {
                return new TBaseOverwriteMetadataContext((TBase) obj, valueMetaData, depth);
            }
            return new PojoOverwriteMetadataContext(obj, valueMetaData, depth);
        }
    }

    static class IgnoreOverwriteMetadataContext implements OverwriteMetadataContext {

        private final int depth;

        IgnoreOverwriteMetadataContext(int depth) {
            this.depth = depth;
        }

        @Override
        public MetadataContext resolve() {
            return this;
        }

        @Override
        public int depth() {
            return depth;
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
        private final int depth;
        private final FieldValueMetaData valueMetaData;

        PojoOverwriteMetadataContext(Object obj, FieldValueMetaData valueMetaData, int depth) {
            this.obj = obj;
            this.depth = depth;
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
        public int depth() {
            return depth;
        }

        @Override
        public FieldValueMetaData valueMetaData() {
            return valueMetaData;
        }
    }

    static class TBaseOverwriteMetadataContext implements OverwriteMetadataContext {

        private final TBase<?, ?> tBase;
        private final FieldValueMetaData valueMetaData;
        private final int depth;
        private final List<FieldOverwriteMetadataContext> contexts;
        private int index;

        TBaseOverwriteMetadataContext(TBase<?, ?> tBase, FieldValueMetaData valueMetaData, int depth) {
            this.tBase = tBase;
            this.valueMetaData = valueMetaData;
            this.depth = depth;
            contexts = getContexts(tBase, depth);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static List<FieldOverwriteMetadataContext> getContexts(TBase tBase, int depth) {
            final Map<? extends TFieldIdEnum, FieldMetaData> metadataMap =
                    ThriftMetadataAccess.getStructMetaDataMap(tBase.getClass());
            final ImmutableList.Builder<FieldOverwriteMetadataContext> contextsBuilder =
                    ImmutableList.builder();
            for (Entry<? extends TFieldIdEnum, FieldMetaData> e : metadataMap.entrySet()) {
                if (!tBase.isSet(e.getKey())) {
                    continue;
                }
                final Object obj = tBase.getFieldValue(e.getKey());
                contextsBuilder.add(new FieldOverwriteMetadataContext(obj, depth + 1,
                                                                      e.getKey(), e.getValue()));
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
        public int depth() {
            return depth;
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
        private final int depth;
        private final List<OverwriteMetadataContext> childContexts;
        private int index;

        MultiOverrideMetadataContext(Set<?> s, SetMetaData setMetaData, int depth) {
            obj = s;
            metaData = setMetaData;
            this.depth = depth;
            final ImmutableList.Builder<OverwriteMetadataContext> childContextsBuilder =
                    ImmutableList.builder();
            for (Object o : s) {
                childContextsBuilder.add(OverwriteMetadataContext.of(o, depth + 1, setMetaData));
            }
            childContexts = childContextsBuilder.build();
        }

        MultiOverrideMetadataContext(List<?> l, ListMetaData listMetaData, int depth) {
            obj = l;
            metaData = listMetaData;
            this.depth = depth;
            final ImmutableList.Builder<OverwriteMetadataContext> childContextsBuilder =
                    ImmutableList.builder();
            for (Object o : l) {
                childContextsBuilder.add(OverwriteMetadataContext.of(o, depth + 1, listMetaData));
            }
            childContexts = childContextsBuilder.build();
        }

        MultiOverrideMetadataContext(Map<?, ?> m, MapMetaData mapMetaData, int depth) {
            obj = m;
            metaData = mapMetaData;
            this.depth = depth;
            final ImmutableList.Builder<OverwriteMetadataContext> childContextsBuilder =
                    ImmutableList.builder();
            for (Entry<?, ?> o : m.entrySet()) {
                childContextsBuilder.add(OverwriteMetadataContext.of(o.getKey(), depth + 1,
                                                                     mapMetaData.keyMetaData));
                childContextsBuilder.add(OverwriteMetadataContext.of(o.getValue(), depth + 1,
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
        public int depth() {
            return depth;
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
        private final int depth;
        private final TFieldIdEnum tFieldIdEnum;
        private final FieldMetaData fieldMetaData;

        FieldOverwriteMetadataContext(Object obj, int depth, TFieldIdEnum tFieldIdEnum,
                                      FieldMetaData fieldMetaData) {
            this.obj = obj;
            this.depth = depth;
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
        public int depth() {
            return depth;
        }

        @Override
        public FieldValueMetaData valueMetaData() {
            return fieldMetaData.valueMetaData;
        }
    }
}
