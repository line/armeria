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

package com.linecorp.armeria.internal.server.annotation;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedDocServicePlugin.toTypeSignature;
import static com.linecorp.armeria.internal.server.annotation.DefaultDescriptiveTypeInfoProvider.isNullable;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nonnull;

import com.linecorp.armeria.server.annotation.Description;
import com.linecorp.armeria.server.docs.ContainerTypeSignature;
import com.linecorp.armeria.server.docs.DescriptionInfo;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfo;
import com.linecorp.armeria.server.docs.DescriptiveTypeInfoProvider;
import com.linecorp.armeria.server.docs.FieldInfo;
import com.linecorp.armeria.server.docs.FieldRequirement;
import com.linecorp.armeria.server.docs.StructInfo;
import com.linecorp.armeria.server.docs.TypeSignature;
import com.linecorp.armeria.server.docs.TypeSignatureType;

enum ReflectiveDescriptiveTypeInfoProvider implements DescriptiveTypeInfoProvider {

    INSTANCE;

    @Nonnull
    @Override
    public DescriptiveTypeInfo newDescriptiveTypeInfo(Object typeDescriptor) {
        final Class<?> clazz = (Class<?>) typeDescriptor;
        final List<FieldInfo> fieldInfos = Arrays.stream(clazz.getDeclaredFields())
                                                 .filter(field -> !Modifier.isStatic(field.getModifiers()))
                                                 .map(ReflectiveDescriptiveTypeInfoProvider::fieldInfo)
                                                 .collect(toImmutableList());
        final DescriptionInfo descriptionInfo = descriptionInfo(clazz);
        return new StructInfo(clazz.getName(), fieldInfos, descriptionInfo);
    }

    private static FieldInfo fieldInfo(Field field) {
        final Type type = field.getGenericType();
        TypeSignature typeSignature = toTypeSignature(type);
        final DescriptionInfo descriptionInfo = descriptionInfo(field);
        final FieldRequirement fieldRequirement;
        if (typeSignature.type() == TypeSignatureType.OPTIONAL) {
            typeSignature = ((ContainerTypeSignature) typeSignature).typeParameters().get(0);
            fieldRequirement = FieldRequirement.OPTIONAL;
        } else {
            if (isNullable(field)) {
                fieldRequirement = FieldRequirement.OPTIONAL;
            } else {
                fieldRequirement = FieldRequirement.REQUIRED;
            }
        }
        return FieldInfo.builder(field.getName(), typeSignature)
                        .requirement(fieldRequirement)
                        .descriptionInfo(descriptionInfo)
                        .build();
    }

    private static DescriptionInfo descriptionInfo(AnnotatedElement element) {
        final Description description = AnnotationUtil.findFirst(element, Description.class);
        if (description == null) {
            return DescriptionInfo.empty();
        } else {
            return DescriptionInfo.from(description);
        }
    }
}
