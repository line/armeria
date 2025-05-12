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

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.linecorp.armeria.common.logging.MaskingStructs.Parent.Child.Inner1.BeanAnn1;
import com.linecorp.armeria.common.logging.MaskingStructs.Parent.Inner4.BeanAnn4;

@SuppressWarnings("checkstyle:VisibilityModifier")
public final class MaskingStructs {

    public static class Parent {

        @Retention(RetentionPolicy.RUNTIME)
        public @interface FieldAnn4 {}

        @BeanAnn4
        public static class Inner4 {
            @Retention(RetentionPolicy.RUNTIME)
            public @interface BeanAnn4 {
            }

            public String ann4 = "ann4";
        }

        @FieldAnn4
        public Inner4 inner4 = new Inner4();

        public static class Child extends Parent {

            @Retention(RetentionPolicy.RUNTIME)
            public @interface ListFieldAnn1 {
                FieldAnn1[] value();
            }

            @Repeatable(ListFieldAnn1.class)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface FieldAnn1 {
                String hello() default "world";
            }

            @BeanAnn1
            public static class Inner1 {
                @Retention(RetentionPolicy.RUNTIME)
                public @interface BeanAnn1 {
                }

                public String ann1 = "ann1";
            }

            @FieldAnn1
            public Inner1 inner1 = new Inner1();

            @FieldAnn1(hello = "Ann2")
            @Retention(RetentionPolicy.RUNTIME)
            public @interface FieldAnn2 {}

            @FieldAnn2
            public String ann2 = "ann2";

            @FieldAnn1(hello = "Ann3-1")
            @FieldAnn1(hello = "Ann3-2")
            @Retention(RetentionPolicy.RUNTIME)
            @JacksonAnnotationsInside
            public @interface FieldAnn3 {}

            @FieldAnn3
            public String ann3 = "ann3";
        }

        public static class SimpleFoo {

            @Retention(RetentionPolicy.RUNTIME)
            public @interface HelloFieldAnn {
            }

            @Retention(RetentionPolicy.RUNTIME)
            public @interface HelloStructAnn {
            }

            @HelloStructAnn
            public static class InnerFoo {
                public String hello = "world";
                public int intVal = 42;

                @Retention(RetentionPolicy.RUNTIME)
                public @interface Masker {}
                @Masker
                public String masked = "masked";

                @Override
                public boolean equals(Object o) {
                    if (this == o) {
                        return true;
                    }
                    if (o == null || getClass() != o.getClass()) {
                        return false;
                    }
                    final InnerFoo innerFoo = (InnerFoo) o;
                    return intVal == innerFoo.intVal && Objects.equal(hello, innerFoo.hello);
                }

                @Override
                public int hashCode() {
                    return Objects.hashCode(hello, intVal);
                }

                @Override
                public String toString() {
                    return MoreObjects.toStringHelper(this)
                                      .add("hello", hello)
                                      .add("intVal", intVal)
                                      .add("masked", masked)
                                      .toString();
                }
            }

            @HelloFieldAnn
            public InnerFoo inner = new InnerFoo();

            @Override
            public boolean equals(Object o) {
                if (this == o) {
                    return true;
                }
                if (o == null || getClass() != o.getClass()) {
                    return false;
                }
                final SimpleFoo simpleFoo = (SimpleFoo) o;
                return Objects.equal(inner, simpleFoo.inner);
            }

            @Override
            public int hashCode() {
                return Objects.hashCode(inner);
            }

            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this)
                                  .add("inner", inner)
                                  .toString();
            }
        }
    }

    private MaskingStructs() {}
}
