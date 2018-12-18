/*
 * Copyright 2017 LINE Corporation
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

/**
 * Specifies which element should be converted by {@link RequestConverterFunction}.
 *
 * <p>A {@link RequestObject} can be implicitly applied when:
 * <ul>
 *     <li>A parameter is neither annotated nor automatically injected type in an annotated method.</li>
 *     <li>A {@link RequestConverter} annotation is specified on a parameter in an annotated method
 *     or a setter of a bean.</li>
 *     <li>A {@link RequestConverter} annotation is specified on a field of a bean.</li>
 *     <li>A {@link RequestConverter} annotation is specified on a method or constructor which has
 *     only one parameter.</li>
 * </ul>
 * The following example shows when to apply a {@link RequestObject} implicitly.
 * <pre>{@code
 * > @RequestConverter(ErinConverter.class)
 * > @RequestConverter(FrankConverter.class)
 * > public class CompositeBean {
 * >     private final Alice a;
 * >
 * >     @RequestConverter(BobConverter.class)      // @RequestObject would be applied implicitly.
 * >     private Bob b;
 * >
 * >     private Charlie c;
 * >     private David d;
 * >     private Erin e;
 * >
 * >     @RequestObject
 * >     private Frank f;                           // Would be converted by the class-level converter.
 * >
 * >     private String g;                          // No conversion would be performed. 'null' would be set.
 * >
 * >     @RequestConverter(AliceConverter.class)    // @RequestObject would be applied implicitly.
 * >     public CompositeBean(Alice a) { ... }
 * >
 * >     @RequestConverter(CharlieConverter.class)  // @RequestObject would be applied implicitly.
 * >     public void setCharlie(Charlie c) { ... }
 * >
 * >     // @RequestObject would be applied implicitly.
 * >     public void setDavidAndErin(@RequestConverter(DavidConverter.class) David d, Erin e) { ... }
 * > }
 * }</pre>
 * @see RequestConverterFunction
 * @see RequestConverter
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({
        ElementType.METHOD,
        ElementType.PARAMETER,
        ElementType.CONSTRUCTOR,
        ElementType.FIELD
})
public @interface RequestObject {}
