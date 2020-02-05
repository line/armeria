/*
 * Copyright 2019 LINE Corporation
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.internal.common.thrift.TApplicationExceptions;

abstract class AbstractThriftMessageClassFinder implements Supplier<Class<?>> {
    private static final Logger logger = LoggerFactory.getLogger(AbstractThriftMessageClassFinder.class);

    @Nullable
    static Class<?> getClassByName(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException ex) {
            logger.warn("Can't find a class: {}", className, ex);
        }
        return null;
    }

    @Nullable
    static Class<?> getMatchedClass(@Nullable Class<?> clazz) {
        if (clazz == null) {
            return null;
        }
        // Note, we need to check
        // if the class is abstract, because abstract class does not have metaDataMap
        // if the class has no-arg constructor, because FieldMetaData.getStructMetaDataMap
        //   calls clazz.newInstance
        if (isTBase(clazz) && !isAbstract(clazz) && hasNoArgConstructor(clazz)) {
            return clazz;
        }

        if (isTApplicationException(clazz)) {
            return clazz;
        }

        if (isTApplicationExceptions(clazz)) {
            return TApplicationException.class;
        }

        return null;
    }

    static boolean isTBase(Class<?> clazz) {
        return TBase.class.isAssignableFrom(clazz);
    }

    private static boolean isTApplicationExceptions(Class<?> clazz) {
        return clazz == TApplicationExceptions.class;
    }

    private static boolean isTApplicationException(Class<?> clazz) {
        return TApplicationException.class.isAssignableFrom(clazz);
    }

    private static boolean isAbstract(Class<?> clazz) {
        return Modifier.isAbstract(clazz.getModifiers());
    }

    private static boolean hasNoArgConstructor(Class<?> clazz) {
        final Constructor<?>[] allConstructors = clazz.getConstructors();
        for (Constructor<?> ctor : allConstructors) {
            final Class<?>[] pType = ctor.getParameterTypes();
            if (pType.length == 0) {
                return true;
            }
        }

        return false;
    }
}
