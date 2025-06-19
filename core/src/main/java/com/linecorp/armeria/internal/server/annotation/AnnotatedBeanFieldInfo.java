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

package com.linecorp.armeria.internal.server.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.Map;
import java.util.function.Function;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.BeanFieldInfo;

final class AnnotatedBeanFieldInfo implements BeanFieldInfo {

    private final String httpElementName;
    private final Map<Class<?>, Annotation> typeElementMap;
    private final Map<Class<?>, Annotation> typeMap;

    AnnotatedBeanFieldInfo(AnnotatedElement typeElement, Class<?> type, String httpElementName) {
        this.httpElementName = httpElementName;
        typeElementMap = annotationMap(typeElement);
        typeMap = annotationMap(type);
    }

    private static Map<Class<?>, Annotation> annotationMap(AnnotatedElement annotatedElement) {
        return AnnotationUtil.getAllAnnotations(annotatedElement).stream()
                             .collect(ImmutableMap.toImmutableMap(Annotation::annotationType,
                                                                  Function.identity(),
                                                                  (a, b) -> a));
    }

    @Override
    public String name() {
        return httpElementName;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public <T extends Annotation> T getFieldAnnotation(Class<T> annotationClass) {
        return (T) typeElementMap.get(annotationClass);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public <T extends Annotation> T getClassAnnotation(Class<T> annotationClass) {
        return (T) typeMap.get(annotationClass);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("httpElementName", httpElementName)
                          .add("typeElementMap", typeElementMap)
                          .add("typeMap", typeMap)
                          .toString();
    }
}
