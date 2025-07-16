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

package com.linecorp.armeria.common.logging;

import java.lang.annotation.Annotation;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Holds information about a POJO field.
 * Users may use the information conveyed in this object to decide whether to mask a field
 * via {@link FieldMasker}.
 *
 * <p>e.g. Assume a {@link BeanFieldInfo} representing the {@code inner} field.
 * {@link #getFieldAnnotation(Class)} will hold information about the {@code Foo#inner} field,
 * and {@link #getClassAnnotation(Class)} will hold information about the {@code Inner} class.
 * <pre>{@code
 * class Foo {
 *     public Inner inner;
 * }
 * }</pre>
 */
@UnstableApi
public interface BeanFieldInfo {

    /**
     * A convenience method which searches for all annotations associated with this
     * {@link BeanFieldInfo}. This method invokes {@link #getFieldAnnotation(Class)}
     * and {@link #getClassAnnotation(Class)} sequentially to find the first non-null
     * annotation of type {@param annotationClass}.
     */
    @Nullable
    default <T extends Annotation> T getAnnotation(Class<T> annotationClass)  {
        final T propertyAnnotation = getFieldAnnotation(annotationClass);
        if (propertyAnnotation != null) {
            return propertyAnnotation;
        }
        return getClassAnnotation(annotationClass);
    }

    /**
     * The name of the field.
     */
    String name();

    /**
     * Returns an annotation on the specified field.
     */
    @Nullable
    <T extends Annotation> T getFieldAnnotation(Class<T> annotationClass);

    /**
     * Returns an annotation on the class of the field.
     */
    @Nullable
    <T extends Annotation> T getClassAnnotation(Class<T> annotationClass);
}
