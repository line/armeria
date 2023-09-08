/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server.protobuf;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.server.protobuf.ProtobufRequestConverterFunction.getMessageBuilder;
import static com.linecorp.armeria.server.protobuf.ProtobufRequestConverterFunctionProvider.isProtobufMessage;
import static java.util.Objects.requireNonNull;

import java.util.List;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfo;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfoProvider;
import com.linecorp.armeria.server.docs.EnumInfo;
import com.linecorp.armeria.server.docs.EnumValueInfo;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;

/**
 * A {@link DescriptiveTypeInfoProvider} to create a {@link DescriptiveTypeInfo}
 * from a protobuf {@link Message}.
 */
@UnstableApi
public final class ProtobufDescriptiveTypeInfoProvider implements DescriptiveTypeInfoProvider {

    @VisibleForTesting
    static final TypeSignature BOOL = TypeSignature.ofBase("bool");
    @VisibleForTesting
    static final TypeSignature INT32 = TypeSignature.ofBase("int32");
    @VisibleForTesting
    static final TypeSignature INT64 = TypeSignature.ofBase("int64");
    @VisibleForTesting
    static final TypeSignature UINT32 = TypeSignature.ofBase("uint32");
    @VisibleForTesting
    static final TypeSignature UINT64 = TypeSignature.ofBase("uint64");
    @VisibleForTesting
    static final TypeSignature SINT32 = TypeSignature.ofBase("sint32");
    @VisibleForTesting
    static final TypeSignature SINT64 = TypeSignature.ofBase("sint64");
    @VisibleForTesting
    static final TypeSignature FLOAT = TypeSignature.ofBase("float");
    @VisibleForTesting
    static final TypeSignature DOUBLE = TypeSignature.ofBase("double");
    @VisibleForTesting
    static final TypeSignature FIXED32 = TypeSignature.ofBase("fixed32");
    @VisibleForTesting
    static final TypeSignature FIXED64 = TypeSignature.ofBase("fixed64");
    @VisibleForTesting
    static final TypeSignature SFIXED32 = TypeSignature.ofBase("sfixed32");
    @VisibleForTesting
    static final TypeSignature SFIXED64 = TypeSignature.ofBase("sfixed64");
    @VisibleForTesting
    static final TypeSignature STRING = TypeSignature.ofBase("string");
    @VisibleForTesting
    static final TypeSignature BYTES = TypeSignature.ofBase("bytes");
    @VisibleForTesting
    static final TypeSignature UNKNOWN = TypeSignature.ofBase("unknown");

    @Nullable
    @Override
    public DescriptiveTypeInfo newDescriptiveTypeInfo(Object typeDescriptor) {
        requireNonNull(typeDescriptor, "typeDescriptor");

        if (typeDescriptor instanceof Descriptor) {
            return newStructInfo((Descriptor) typeDescriptor);
        }
        if (typeDescriptor instanceof EnumDescriptor) {
            return newEnumInfo((EnumDescriptor) typeDescriptor);
        }

        if (!(typeDescriptor instanceof Class)) {
            return null;
        }

        final Class<?> clazz = (Class<?>) typeDescriptor;
        if (!isProtobufMessage(clazz)) {
            return null;
        }
        final Message.Builder messageBuilder = getMessageBuilder(clazz);
        final Descriptor descriptorForType = messageBuilder.getDescriptorForType();

        return newStructInfo(descriptorForType).withAlias(clazz.getName());
    }

    /**
     * Creates a new {@link StructInfo} from the specified {@link Descriptor}.
     */
    @UnstableApi
    public static StructInfo newStructInfo(Descriptor descriptor) {
        requireNonNull(descriptor, "descriptor");
        return new StructInfo(descriptor.getFullName(), newFieldInfos(descriptor));
    }

    private static List<FieldInfo> newFieldInfos(Descriptor descriptor) {
        return descriptor.getFields().stream()
                         .map(fieldDescriptor -> {
                             final TypeSignature typeSignature = newFieldTypeInfo(fieldDescriptor);
                             return FieldInfo.builder(fieldDescriptor.getName(), typeSignature)
                                             .requirement(
                                                     fieldDescriptor.isRequired() ? FieldRequirement.REQUIRED
                                                                                  : FieldRequirement.OPTIONAL)
                                             .build();
                         })
                         .collect(toImmutableList());
    }

    @VisibleForTesting
    static TypeSignature newFieldTypeInfo(FieldDescriptor fieldDescriptor) {
        if (fieldDescriptor.isMapField()) {
            return TypeSignature.ofMap(
                    newFieldTypeInfo(fieldDescriptor.getMessageType().findFieldByNumber(1)),
                    newFieldTypeInfo(fieldDescriptor.getMessageType().findFieldByNumber(2)));
        }
        final TypeSignature fieldType;
        switch (fieldDescriptor.getType()) {
            case BOOL:
                fieldType = BOOL;
                break;
            case BYTES:
                fieldType = BYTES;
                break;
            case DOUBLE:
                fieldType = DOUBLE;
                break;
            case FIXED32:
                fieldType = FIXED32;
                break;
            case FIXED64:
                fieldType = FIXED64;
                break;
            case FLOAT:
                fieldType = FLOAT;
                break;
            case INT32:
                fieldType = INT32;
                break;
            case INT64:
                fieldType = INT64;
                break;
            case SFIXED32:
                fieldType = SFIXED32;
                break;
            case SFIXED64:
                fieldType = SFIXED64;
                break;
            case SINT32:
                fieldType = SINT32;
                break;
            case SINT64:
                fieldType = SINT64;
                break;
            case STRING:
                fieldType = STRING;
                break;
            case UINT32:
                fieldType = UINT32;
                break;
            case UINT64:
                fieldType = UINT64;
                break;
            case MESSAGE:
                fieldType = descriptiveMessageSignature(fieldDescriptor.getMessageType());
                break;
            case GROUP:
                // This type has been deprecated since the launch of protocol buffers to open source.
                // There is no real metadata for this in the descriptor, so we just treat as UNKNOWN
                // since it shouldn't happen in practice anyway.
                fieldType = UNKNOWN;
                break;
            case ENUM:
                fieldType = TypeSignature.ofEnum(
                        fieldDescriptor.getEnumType().getFullName(), fieldDescriptor.getEnumType());
                break;
            default:
                fieldType = UNKNOWN;
                break;
        }
        return fieldDescriptor.isRepeated() ? TypeSignature.ofIterable("repeated", fieldType) : fieldType;
    }

    private static TypeSignature descriptiveMessageSignature(Descriptor descriptor) {
        return TypeSignature.ofStruct(descriptor.getFullName(), descriptor);
    }

    @VisibleForTesting
    static EnumInfo newEnumInfo(EnumDescriptor enumDescriptor) {
        return new EnumInfo(
                enumDescriptor.getFullName(),
                enumDescriptor.getValues().stream()
                              .map(d -> new EnumValueInfo(d.getName(), d.getNumber()))
                              .collect(toImmutableList()));
    }
}
