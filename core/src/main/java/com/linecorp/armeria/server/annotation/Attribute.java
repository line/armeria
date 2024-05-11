/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AttributeKey;

/**
 * Annotation for mapping an attribute of the given {@link AttributeKey}, retrieved
 * from a {@link RequestContext}, onto the following elements.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@UnstableApi
public @interface Attribute {

    /**
     * The class of the {@link AttributeKey} to bind to. If you created an {@link AttributeKey} with
     * {@code AttributeKey.valueOf(MyAttributeKeys.class, "INT_ATTR")},
     * the {@link #prefix()} should be {@code MyAttributeKeys.class}.
     * See <a href="https://armeria.dev/docs/advanced-custom-attributes/">advanced-customer-attributes</a>.
     */
    Class<?> prefix() default Attribute.class;

    /**
     * The name of the {@link AttributeKey} to bind to.
     * You might also want to specify the {@link #prefix()}.
     */
    String value();
}
