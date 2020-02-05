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
package com.linecorp.armeria.internal.server.annotation;

import static com.linecorp.armeria.internal.server.annotation.AnnotationUtil.find;
import static com.linecorp.armeria.internal.server.annotation.AnnotationUtil.findAll;
import static com.linecorp.armeria.internal.server.annotation.AnnotationUtil.findDeclared;
import static com.linecorp.armeria.internal.server.annotation.AnnotationUtil.findInherited;
import static com.linecorp.armeria.internal.server.annotation.AnnotationUtil.getAllAnnotations;
import static com.linecorp.armeria.internal.server.annotation.AnnotationUtil.getAnnotations;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.EnumSet;
import java.util.List;

import org.junit.Test;

import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.FixedValue;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.internal.server.annotation.AnnotationUtil.FindOption;

public class AnnotationUtilTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface TestMetaOfMetaAnnotation {}

    @TestMetaOfMetaAnnotation
    @Retention(RetentionPolicy.RUNTIME)
    @interface TestMetaAnnotation {
        String value();
    }

    @TestMetaAnnotation("TestAnnotation")
    @Retention(RetentionPolicy.RUNTIME)
    @interface TestAnnotation {
        String value();
    }

    @TestMetaAnnotation("TestRepeatables")
    @Retention(RetentionPolicy.RUNTIME)
    @interface TestRepeatables {
        TestRepeatable[] value();
    }

    @TestMetaAnnotation("TestRepeatable")
    @Retention(RetentionPolicy.RUNTIME)
    @Repeatable(TestRepeatables.class)
    @interface TestRepeatable {
        String value();
    }

    @TestRepeatable("class-level:TestClass:Repeatable1")
    @TestRepeatable("class-level:TestClass:Repeatable2")
    @TestAnnotation("class-level:TestClass")
    static class TestClass {
        @TestAnnotation("method-level:directlyPresent")
        public void directlyPresent() {}

        @TestRepeatable("method-level:directlyPresentRepeatableSingle")
        public void directlyPresentRepeatableSingle() {}

        @TestRepeatable("method-level:directlyPresentRepeatableMulti:1")
        @TestRepeatable("method-level:directlyPresentRepeatableMulti:2")
        @TestRepeatable("method-level:directlyPresentRepeatableMulti:3")
        public void directlyPresentRepeatableMulti() {}
    }

    @TestRepeatable("class-level:TestChildClass:Repeatable1")
    @TestRepeatable("class-level:TestChildClass:Repeatable2")
    @TestAnnotation("class-level:TestChildClass")
    static class TestChildClass extends TestClass {}

    @TestRepeatable("class-level:TestGrandChildClass:Repeatable1")
    @TestRepeatable("class-level:TestGrandChildClass:Repeatable2")
    @TestAnnotation("class-level:TestGrandChildClass")
    static class TestGrandChildClass extends TestChildClass {}

    @TestRepeatable("class-level:SingleRepeatableTestClass")
    static class SingleRepeatableTestClass {}

    @TestRepeatable("class-level:SingleRepeatableTestChildClass")
    static class SingleRepeatableTestChildClass extends SingleRepeatableTestClass {}

    @TestRepeatable("class-level:SingleRepeatableTestGrandChildClass")
    static class SingleRepeatableTestGrandChildClass extends SingleRepeatableTestChildClass {}

    @TestAnnotation("TestIface")
    interface TestIface {
        default void action() {}
    }

    @TestAnnotation("TestChildIface")
    interface TestChildIface extends TestIface {
        default void moreAction() {}
    }

    @TestAnnotation("TestAnotherIface")
    interface TestAnotherIface {
        default void anotherAction() {}
    }

    @TestAnnotation("TestClassWithIface")
    static class TestClassWithIface implements TestChildIface {}

    @TestAnnotation("TestChildClassWithIface")
    static class TestChildClassWithIface extends TestClassWithIface implements TestAnotherIface {}

    @CyclicAnnotation
    @Retention(RetentionPolicy.RUNTIME)
    @interface CyclicAnnotation {}

    @CyclicAnnotation
    static class TestClassWithCyclicAnnotation {
        @CyclicAnnotation
        public void foo() {}
    }

    @Test
    public void declared() throws NoSuchMethodException {
        List<TestAnnotation> list;

        list = findDeclared(TestClass.class.getMethod("directlyPresent"), TestAnnotation.class);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).value()).isEqualTo("method-level:directlyPresent");

        list = findDeclared(TestClass.class, TestAnnotation.class);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).value()).isEqualTo("class-level:TestClass");

        list = findDeclared(TestChildClass.class, TestAnnotation.class);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).value()).isEqualTo("class-level:TestChildClass");

        list = findDeclared(TestGrandChildClass.class, TestAnnotation.class);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).value()).isEqualTo("class-level:TestGrandChildClass");
    }

    @Test
    public void declared_repeatable() throws NoSuchMethodException {
        List<TestRepeatable> list;

        list = findDeclared(TestClass.class.getMethod("directlyPresentRepeatableSingle"),
                            TestRepeatable.class);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).value()).isEqualTo("method-level:directlyPresentRepeatableSingle");

        list = findDeclared(SingleRepeatableTestClass.class, TestRepeatable.class);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).value()).isEqualTo("class-level:SingleRepeatableTestClass");

        list = findDeclared(SingleRepeatableTestChildClass.class, TestRepeatable.class);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).value()).isEqualTo("class-level:SingleRepeatableTestChildClass");

        list = findDeclared(SingleRepeatableTestGrandChildClass.class, TestRepeatable.class);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).value()).isEqualTo("class-level:SingleRepeatableTestGrandChildClass");
    }

    @Test
    public void declared_repeatable_multi() throws NoSuchMethodException {
        List<TestRepeatable> list;

        list = findDeclared(TestClass.class.getMethod("directlyPresentRepeatableMulti"),
                            TestRepeatable.class);
        assertThat(list).hasSize(3);
        assertThat(list.get(0).value()).isEqualTo("method-level:directlyPresentRepeatableMulti:1");
        assertThat(list.get(1).value()).isEqualTo("method-level:directlyPresentRepeatableMulti:2");
        assertThat(list.get(2).value()).isEqualTo("method-level:directlyPresentRepeatableMulti:3");

        list = findDeclared(TestClass.class, TestRepeatable.class);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).value()).isEqualTo("class-level:TestClass:Repeatable1");
        assertThat(list.get(1).value()).isEqualTo("class-level:TestClass:Repeatable2");

        list = findDeclared(TestChildClass.class, TestRepeatable.class);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).value()).isEqualTo("class-level:TestChildClass:Repeatable1");
        assertThat(list.get(1).value()).isEqualTo("class-level:TestChildClass:Repeatable2");

        list = findDeclared(TestGrandChildClass.class, TestRepeatable.class);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).value()).isEqualTo("class-level:TestGrandChildClass:Repeatable1");
        assertThat(list.get(1).value()).isEqualTo("class-level:TestGrandChildClass:Repeatable2");
    }

    @Test
    public void lookupSuperClass() {
        List<TestAnnotation> list;

        list = findInherited(TestClass.class, TestAnnotation.class);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).value()).isEqualTo("class-level:TestClass");

        list = findInherited(TestChildClass.class, TestAnnotation.class);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).value()).isEqualTo("class-level:TestChildClass");
        assertThat(list.get(1).value()).isEqualTo("class-level:TestClass");

        list = findInherited(TestGrandChildClass.class, TestAnnotation.class);
        assertThat(list).hasSize(3);
        assertThat(list.get(0).value()).isEqualTo("class-level:TestGrandChildClass");
        assertThat(list.get(1).value()).isEqualTo("class-level:TestChildClass");
        assertThat(list.get(2).value()).isEqualTo("class-level:TestClass");

        list = find(TestGrandChildClass.class, TestAnnotation.class,
                    FindOption.LOOKUP_SUPER_CLASSES, FindOption.COLLECT_SUPER_CLASSES_FIRST);
        assertThat(list).hasSize(3);
        assertThat(list.get(0).value()).isEqualTo("class-level:TestClass");
        assertThat(list.get(1).value()).isEqualTo("class-level:TestChildClass");
        assertThat(list.get(2).value()).isEqualTo("class-level:TestGrandChildClass");
    }

    @Test
    public void lookupSuperClass_repeatable() {
        List<TestRepeatable> list;

        list = findInherited(SingleRepeatableTestClass.class, TestRepeatable.class);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).value()).isEqualTo("class-level:SingleRepeatableTestClass");

        list = findInherited(SingleRepeatableTestChildClass.class, TestRepeatable.class);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).value()).isEqualTo("class-level:SingleRepeatableTestChildClass");
        assertThat(list.get(1).value()).isEqualTo("class-level:SingleRepeatableTestClass");

        list = findInherited(SingleRepeatableTestGrandChildClass.class, TestRepeatable.class);
        assertThat(list).hasSize(3);
        assertThat(list.get(0).value()).isEqualTo("class-level:SingleRepeatableTestGrandChildClass");
        assertThat(list.get(1).value()).isEqualTo("class-level:SingleRepeatableTestChildClass");
        assertThat(list.get(2).value()).isEqualTo("class-level:SingleRepeatableTestClass");

        list = find(SingleRepeatableTestGrandChildClass.class, TestRepeatable.class,
                    FindOption.LOOKUP_SUPER_CLASSES, FindOption.COLLECT_SUPER_CLASSES_FIRST);
        assertThat(list).hasSize(3);
        assertThat(list.get(0).value()).isEqualTo("class-level:SingleRepeatableTestClass");
        assertThat(list.get(1).value()).isEqualTo("class-level:SingleRepeatableTestChildClass");
        assertThat(list.get(2).value()).isEqualTo("class-level:SingleRepeatableTestGrandChildClass");
    }

    @Test
    public void lookupSuperClass_repeatable_multi() {
        List<TestRepeatable> list;

        list = findInherited(TestClass.class, TestRepeatable.class);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).value()).isEqualTo("class-level:TestClass:Repeatable1");
        assertThat(list.get(1).value()).isEqualTo("class-level:TestClass:Repeatable2");

        list = findInherited(TestChildClass.class, TestRepeatable.class);
        assertThat(list).hasSize(4);
        assertThat(list.get(0).value()).isEqualTo("class-level:TestChildClass:Repeatable1");
        assertThat(list.get(1).value()).isEqualTo("class-level:TestChildClass:Repeatable2");
        assertThat(list.get(2).value()).isEqualTo("class-level:TestClass:Repeatable1");
        assertThat(list.get(3).value()).isEqualTo("class-level:TestClass:Repeatable2");

        list = findInherited(TestGrandChildClass.class, TestRepeatable.class);
        assertThat(list).hasSize(6);
        assertThat(list.get(0).value()).isEqualTo("class-level:TestGrandChildClass:Repeatable1");
        assertThat(list.get(1).value()).isEqualTo("class-level:TestGrandChildClass:Repeatable2");
        assertThat(list.get(2).value()).isEqualTo("class-level:TestChildClass:Repeatable1");
        assertThat(list.get(3).value()).isEqualTo("class-level:TestChildClass:Repeatable2");
        assertThat(list.get(4).value()).isEqualTo("class-level:TestClass:Repeatable1");
        assertThat(list.get(5).value()).isEqualTo("class-level:TestClass:Repeatable2");

        list = find(TestGrandChildClass.class, TestRepeatable.class,
                    FindOption.LOOKUP_SUPER_CLASSES, FindOption.COLLECT_SUPER_CLASSES_FIRST);
        assertThat(list).hasSize(6);
        assertThat(list.get(0).value()).isEqualTo("class-level:TestClass:Repeatable1");
        assertThat(list.get(1).value()).isEqualTo("class-level:TestClass:Repeatable2");
        assertThat(list.get(2).value()).isEqualTo("class-level:TestChildClass:Repeatable1");
        assertThat(list.get(3).value()).isEqualTo("class-level:TestChildClass:Repeatable2");
        assertThat(list.get(4).value()).isEqualTo("class-level:TestGrandChildClass:Repeatable1");
        assertThat(list.get(5).value()).isEqualTo("class-level:TestGrandChildClass:Repeatable2");
    }

    @Test
    public void lookupMetaAnnotations_declared() {
        List<TestMetaAnnotation> list;

        for (final Class<?> clazz : ImmutableList.of(TestClass.class,
                                                     TestChildClass.class,
                                                     TestGrandChildClass.class)) {
            list = find(clazz, TestMetaAnnotation.class, EnumSet.of(FindOption.LOOKUP_META_ANNOTATIONS));
            assertThat(list).hasSize(2);
            assertThat(list.get(0).value()).isEqualTo("TestRepeatables");   // From the container annotation.
            assertThat(list.get(1).value()).isEqualTo("TestAnnotation");
        }

        for (final Class<?> clazz : ImmutableList.of(SingleRepeatableTestClass.class,
                                                     SingleRepeatableTestChildClass.class,
                                                     SingleRepeatableTestGrandChildClass.class)) {
            list = find(clazz, TestMetaAnnotation.class, EnumSet.of(FindOption.LOOKUP_META_ANNOTATIONS));
            assertThat(list).hasSize(1);
            assertThat(list.get(0).value()).isEqualTo("TestRepeatable");
        }
    }

    @Test
    public void findAll_includingRepeatable() {
        List<TestMetaAnnotation> list;

        list = findAll(SingleRepeatableTestClass.class, TestMetaAnnotation.class);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).value()).isEqualTo("TestRepeatable");

        list = findAll(SingleRepeatableTestChildClass.class, TestMetaAnnotation.class);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).value()).isEqualTo("TestRepeatable");
        assertThat(list.get(1).value()).isEqualTo("TestRepeatable");

        list = findAll(SingleRepeatableTestGrandChildClass.class, TestMetaAnnotation.class);
        assertThat(list).hasSize(3);
        assertThat(list.get(0).value()).isEqualTo("TestRepeatable");
        assertThat(list.get(1).value()).isEqualTo("TestRepeatable");
        assertThat(list.get(2).value()).isEqualTo("TestRepeatable");
    }

    @Test
    public void findAll_includingRepeatable_multi() {
        List<TestMetaAnnotation> list;

        list = findAll(TestClass.class, TestMetaAnnotation.class);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).value()).isEqualTo("TestRepeatables");
        assertThat(list.get(1).value()).isEqualTo("TestAnnotation");

        list = findAll(TestChildClass.class, TestMetaAnnotation.class);
        assertThat(list).hasSize(4);
        assertThat(list.get(0).value()).isEqualTo("TestRepeatables");
        assertThat(list.get(1).value()).isEqualTo("TestAnnotation");
        assertThat(list.get(2).value()).isEqualTo("TestRepeatables");
        assertThat(list.get(3).value()).isEqualTo("TestAnnotation");

        list = findAll(TestGrandChildClass.class, TestMetaAnnotation.class);
        assertThat(list).hasSize(6);
        assertThat(list.get(0).value()).isEqualTo("TestRepeatables");
        assertThat(list.get(1).value()).isEqualTo("TestAnnotation");
        assertThat(list.get(2).value()).isEqualTo("TestRepeatables");
        assertThat(list.get(3).value()).isEqualTo("TestAnnotation");
        assertThat(list.get(4).value()).isEqualTo("TestRepeatables");
        assertThat(list.get(5).value()).isEqualTo("TestAnnotation");
    }

    @Test
    public void findAll_interfaces() {
        List<TestAnnotation> list;

        list = findAll(TestClassWithIface.class, TestAnnotation.class);
        assertThat(list).hasSize(3);
        assertThat(list.get(0).value()).isEqualTo("TestClassWithIface");
        assertThat(list.get(1).value()).isEqualTo("TestChildIface");
        assertThat(list.get(2).value()).isEqualTo("TestIface");

        list = findAll(TestChildClassWithIface.class, TestAnnotation.class);
        assertThat(list).hasSize(5);
        assertThat(list.get(0).value()).isEqualTo("TestChildClassWithIface");
        assertThat(list.get(1).value()).isEqualTo("TestAnotherIface");
        assertThat(list.get(2).value()).isEqualTo("TestClassWithIface");
        assertThat(list.get(3).value()).isEqualTo("TestChildIface");
        assertThat(list.get(4).value()).isEqualTo("TestIface");
    }

    @Test
    public void findAll_metaAnnotationOfMetaAnnotation() {
        List<TestMetaOfMetaAnnotation> list;

        list = findAll(TestClass.class, TestMetaOfMetaAnnotation.class);
        assertThat(list).hasSize(2);

        list = findAll(TestChildClass.class, TestMetaOfMetaAnnotation.class);
        assertThat(list).hasSize(4);

        list = findAll(TestGrandChildClass.class, TestMetaOfMetaAnnotation.class);
        assertThat(list).hasSize(6);
    }

    @Test
    public void findAll_cyclic() throws Exception {
        List<CyclicAnnotation> list = findAll(TestClassWithCyclicAnnotation.class, CyclicAnnotation.class);
        assertThat(list).hasSize(1);
        assertThat(list.get(0)).isInstanceOf(CyclicAnnotation.class);

        list = findAll(TestClassWithCyclicAnnotation.class.getDeclaredMethod("foo"), CyclicAnnotation.class);
        assertThat(list).hasSize(1);
        assertThat(list.get(0)).isInstanceOf(CyclicAnnotation.class);
    }

    @Test
    public void cglibProxy() {
        final Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(TestGrandChildClass.class);
        enhancer.setCallback((FixedValue) () -> null);
        final TestGrandChildClass proxy = (TestGrandChildClass) enhancer.create();

        // No declared annotations on proxy.
        assertThat(findDeclared(proxy.getClass(), TestAnnotation.class)).isEmpty();
        // No declared annotations on proxy, so no meta annotations as well.
        assertThat(find(proxy.getClass(), TestMetaAnnotation.class,
                        EnumSet.of(FindOption.LOOKUP_META_ANNOTATIONS))).isEmpty();

        final List<TestAnnotation> list1 = findAll(proxy.getClass(), TestAnnotation.class);
        assertThat(list1).hasSize(3);
        assertThat(list1.get(0).value()).isEqualTo("class-level:TestGrandChildClass");
        assertThat(list1.get(1).value()).isEqualTo("class-level:TestChildClass");
        assertThat(list1.get(2).value()).isEqualTo("class-level:TestClass");

        final List<TestMetaAnnotation> list2 = findAll(proxy.getClass(), TestMetaAnnotation.class);
        assertThat(list2).hasSize(6);
        assertThat(list2.get(0).value()).isEqualTo("TestRepeatables");
        assertThat(list2.get(1).value()).isEqualTo("TestAnnotation");
        assertThat(list2.get(2).value()).isEqualTo("TestRepeatables");
        assertThat(list2.get(3).value()).isEqualTo("TestAnnotation");
        assertThat(list2.get(4).value()).isEqualTo("TestRepeatables");
        assertThat(list2.get(5).value()).isEqualTo("TestAnnotation");
    }

    @Test
    public void getAnnotations_declared() {
        final List<Annotation> list = getAnnotations(TestGrandChildClass.class);
        assertThat(list).hasSize(2);
        assertThat(list.get(0)).isInstanceOf(TestRepeatables.class);
        assertThat(list.get(1)).isInstanceOf(TestAnnotation.class);
    }

    @Test
    public void getAnnotations_inherited() {
        final List<Annotation> list =
                getAnnotations(TestGrandChildClass.class, FindOption.LOOKUP_SUPER_CLASSES);
        assertThat(list).hasSize(6);
        assertThat(list.get(0)).isInstanceOf(TestRepeatables.class);
        assertThat(list.get(1)).isInstanceOf(TestAnnotation.class);
        assertThat(list.get(2)).isInstanceOf(TestRepeatables.class);
        assertThat(list.get(3)).isInstanceOf(TestAnnotation.class);
        assertThat(list.get(4)).isInstanceOf(TestRepeatables.class);
        assertThat(list.get(5)).isInstanceOf(TestAnnotation.class);
    }

    @Test
    public void getAnnotations_all() {
        final List<Annotation> list = getAllAnnotations(TestGrandChildClass.class);
        assertThat(list).hasSize(18);
        for (int i = 0; i < 3 * 6;) {
            assertThat(list.get(i++)).isInstanceOf(TestMetaOfMetaAnnotation.class);
            assertThat(list.get(i)).isInstanceOf(TestMetaAnnotation.class);
            assertThat(((TestMetaAnnotation) list.get(i++)).value()).isEqualTo("TestRepeatables");
            assertThat(list.get(i++)).isInstanceOf(TestRepeatables.class);
            assertThat(list.get(i++)).isInstanceOf(TestMetaOfMetaAnnotation.class);
            assertThat(list.get(i)).isInstanceOf(TestMetaAnnotation.class);
            assertThat(((TestMetaAnnotation) list.get(i++)).value()).isEqualTo("TestAnnotation");
            assertThat(list.get(i++)).isInstanceOf(TestAnnotation.class);
        }
    }

    @Test
    public void getAnnotations_all_cyclic() throws Exception {
        List<Annotation> list = getAllAnnotations(TestClassWithCyclicAnnotation.class);
        assertThat(list).hasSize(1);
        assertThat(list.get(0)).isInstanceOf(CyclicAnnotation.class);

        list = getAllAnnotations(TestClassWithCyclicAnnotation.class.getDeclaredMethod("foo"));
        assertThat(list).hasSize(1);
        assertThat(list.get(0)).isInstanceOf(CyclicAnnotation.class);
    }
}
