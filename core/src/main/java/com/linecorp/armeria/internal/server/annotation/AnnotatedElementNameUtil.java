/*
 * Copyright 2018 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Parameter;

import com.google.common.base.Ascii;
import com.google.common.base.CaseFormat;

import com.linecorp.armeria.server.annotation.Attribute;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;

final class AnnotatedElementNameUtil {

    /**
     * Returns the value of {@link Header}, {@link Param}, {@link Attribute} if the value is not blank.
     * If the value is blank, it returns the name of the specified {@code nameRetrievalTarget} object
     * which is an instance of {@link Header}, {@link Param}, {@link Attribute} or {@link Field}.
     */
    static String findName(Object nameRetrievalTarget, String value) {
        requireNonNull(nameRetrievalTarget, "nameRetrievalTarget");
        if (DefaultValues.isSpecified(value)) {
            checkArgument(!value.isEmpty(), "value is empty.");
            return value;
        }
        return getName(nameRetrievalTarget);
    }

    /**
     * Returns the name of the specified element or the default name if it can't get.
     */
    static String getNameOrDefault(Object element, String defaultName) {
        try {
            return getName(element);
        } catch (Exception ignored) {
            return defaultName;
        }
    }

    /**
     * Returns the name of the {@link Parameter} or the {@link Field}.
     * @param element either {@link Parameter} or {@link Field}
     */
    static String getName(Object element) {
        if (element instanceof Parameter) {
            final Parameter parameter = (Parameter) element;
            if (!parameter.isNamePresent()) {
                throw new IllegalArgumentException(
                        "cannot obtain the name of the parameter or field automatically. " +
                        "Please make sure you compiled your code with '-parameters' option. " +
                        "Alternatively, you could specify the name explicitly in the annotation.");
            }
            return parameter.getName();
        }
        if (element instanceof Field) {
            return ((Field) element).getName();
        }
        throw new IllegalArgumentException("cannot find the name: " + element.getClass().getName());
    }

    static String toHeaderName(String name) {
        requireNonNull(name, "name");
        checkArgument(!name.isEmpty(), "name is empty.");

        final String upperCased = Ascii.toUpperCase(name);
        if (name.equals(upperCased)) {
            return CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_HYPHEN, name);
        }
        final String lowerCased = Ascii.toLowerCase(name);
        if (name.equals(lowerCased)) {
            return CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_HYPHEN, name);
        }
        // Ensure that the name does not contain '_'.
        // If it contains '_', we give up to make it lower hyphen case. Just converting it to lower case.
        if (name.indexOf('_') >= 0) {
            return lowerCased;
        }
        if (Ascii.isUpperCase(name.charAt(0))) {
            return CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_HYPHEN, name);
        } else {
            return CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_HYPHEN, name);
        }
    }

    private AnnotatedElementNameUtil() {}
}
