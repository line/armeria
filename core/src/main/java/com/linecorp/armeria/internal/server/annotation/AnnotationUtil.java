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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static org.reflections.ReflectionUtils.getMethods;
import static org.reflections.ReflectionUtils.withName;
import static org.reflections.ReflectionUtils.withParametersCount;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.Iterables;
import com.google.common.collect.MapMaker;

/**
 * A utility class which helps to get annotations from an {@link AnnotatedElement}.
 */
final class AnnotationUtil {

    private static final Logger logger = LoggerFactory.getLogger(AnnotationUtil.class);

    /**
     * Options to be used for finding annotations from an {@link AnnotatedElement}.
     */
    enum FindOption {
        /**
         * Get annotations from super classes of the given {@link AnnotatedElement} if the element is a
         * {@link Class}. Otherwise, this option will be ignored.
         */
        LOOKUP_SUPER_CLASSES,
        /**
         * Get additional annotations from the meta-annotations which annotate the annotations specified
         * on the given {@link AnnotatedElement}.
         */
        LOOKUP_META_ANNOTATIONS,
        /**
         * Collect the annotations specified in the super classes first, the annotations specified on the
         * given {@link AnnotatedElement} will be collected at last. This option will work only with
         * the {@link #LOOKUP_SUPER_CLASSES}.
         */
        COLLECT_SUPER_CLASSES_FIRST
    }

    /**
     * A thread-local hash set of annotation classes that contain cyclic references.
     */
    private static final Set<Class<? extends Annotation>> knownCyclicAnnotationTypes =
            Collections.newSetFromMap(new MapMaker().weakKeys().makeMap());

    static {
        // Add well known JDK annotations with cyclic dependencies which will always be blocklisted.
        knownCyclicAnnotationTypes.add(Documented.class);
        knownCyclicAnnotationTypes.add(Retention.class);
        knownCyclicAnnotationTypes.add(Target.class);
    }

    /**
     * Returns an annotation of the {@code annotationType} if it is found from one of the following:
     * <ul>
     *     <li>the specified {@code element}</li>
     *     <li>the super classes of the specified {@code element} if the {@code element} is a class</li>
     *     <li>the meta-annotations of the annotations specified on the {@code element}
     *     or its super classes</li>
     * </ul>
     * Otherwise, {@code null} will be returned.
     *
     * @param element the {@link AnnotatedElement} to find the first annotation
     * @param annotationType the type of the annotation to find
     */
    @Nullable
    static <T extends Annotation> T findFirst(AnnotatedElement element, Class<T> annotationType) {
        final List<T> found = findAll(element, annotationType);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Returns an annotation of the {@code annotationType} if it is found from the specified {@code element}.
     * Otherwise, the {@code null} will be returned.
     *
     * <p>Note that this method will <em>not</em> find annotations from both the super classes of
     * the {@code element} and the meta-annotations.
     *
     * @param element the {@link AnnotatedElement} to find the first annotation
     * @param annotationType the type of the annotation to find
     */
    @Nullable
    static <T extends Annotation> T findFirstDeclared(AnnotatedElement element,
                                                                Class<T> annotationType) {
        final List<T> found = findDeclared(element, annotationType);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Returns all annotations of the {@code annotationType} which are found from the following.
     * <ul>
     *     <li>the specified {@code element}</li>
     *     <li>the super classes of the specified {@code element} if the {@code element} is a class</li>
     *     <li>the meta-annotations of the annotations specified on the {@code element}
     *     or its super classes</li>
     * </ul>
     *
     * @param element the {@link AnnotatedElement} to find annotations
     * @param annotationType the type of the annotation to find
     */
    static <T extends Annotation> List<T> findAll(AnnotatedElement element, Class<T> annotationType) {
        return find(element, annotationType, EnumSet.of(FindOption.LOOKUP_SUPER_CLASSES,
                                                        FindOption.LOOKUP_META_ANNOTATIONS));
    }

    /**
     * Returns all annotations of the {@code annotationType} which are found from the following.
     * <ul>
     *     <li>the specified {@code element}</li>
     *     <li>the super classes of the specified {@code element} if the {@code element} is a class</li>
     * </ul>
     *
     * <p>Note that this method will <em>not</em> find annotations from the meta-annotations.
     *
     * @param element the {@link AnnotatedElement} to find annotations
     * @param annotationType the type of the annotation to find
     */
    static <T extends Annotation> List<T> findInherited(
            AnnotatedElement element, Class<T> annotationType) {
        return find(element, annotationType, EnumSet.of(FindOption.LOOKUP_SUPER_CLASSES));
    }

    /**
     * Returns all annotations of the {@code annotationType} which are found from the specified
     * {@code element}.
     *
     * <p>Note that this method will <em>not</em> find annotations from both the super classes
     * of the {@code element} and the meta-annotations.
     *
     * @param element the {@link AnnotatedElement} to find annotations
     * @param annotationType the type of the annotation to find
     */
    static <T extends Annotation> List<T> findDeclared(
            AnnotatedElement element, Class<T> annotationType) {
        return find(element, annotationType, EnumSet.noneOf(FindOption.class));
    }

    /**
     * Returns all annotations of the {@code annotationType} searching from the specified {@code element}.
     * The search range depends on the specified {@link FindOption}s.
     *
     * @param element the {@link AnnotatedElement} to find annotations
     * @param annotationType the type of the annotation to find
     * @param findOptions the options to be applied when finding annotations
     */
    static <T extends Annotation> List<T> find(AnnotatedElement element, Class<T> annotationType,
                                               FindOption... findOptions) {
        return find(element, annotationType, EnumSet.copyOf(
                ImmutableList.copyOf(requireNonNull(findOptions, "findOptions"))));
    }

    /**
     * Returns all annotations of the {@code annotationType} searching from the specified {@code element}.
     * The search range depends on the specified {@link FindOption}s.
     *
     * @param element the {@link AnnotatedElement} to find annotations
     * @param annotationType the type of the annotation to find
     * @param findOptions the options to be applied when finding annotations
     */
    static <T extends Annotation> List<T> find(AnnotatedElement element, Class<T> annotationType,
                                               EnumSet<FindOption> findOptions) {
        requireNonNull(element, "element");
        requireNonNull(annotationType, "annotationType");

        final Builder<T> builder = new Builder<>();

        // Repeatable is not a repeatable. So the length of the returning array is 0 or 1.
        final Repeatable[] repeatableAnnotations = annotationType.getAnnotationsByType(Repeatable.class);
        final Class<? extends Annotation> containerType =
                repeatableAnnotations.length > 0 ? repeatableAnnotations[0].value() : null;

        for (final AnnotatedElement e : resolveTargetElements(element, findOptions)) {
            for (final Annotation annotation : e.getDeclaredAnnotations()) {
                if (findOptions.contains(FindOption.LOOKUP_META_ANNOTATIONS)) {
                    findMetaAnnotations(builder, annotation, annotationType, containerType);
                }
                collectAnnotations(builder, annotation, annotationType, containerType);
            }
        }
        return builder.build();
    }

    private static <T extends Annotation> void findMetaAnnotations(
            Builder<T> builder, Annotation annotation,
            Class<T> annotationType, @Nullable Class<? extends Annotation> containerType) {
        findMetaAnnotations(builder, annotation, annotationType, containerType,
                            Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static <T extends Annotation> boolean findMetaAnnotations(
            Builder<T> builder, Annotation annotation,
            Class<T> annotationType, @Nullable Class<? extends Annotation> containerType,
            Set<Class<? extends Annotation>> visitedAnnotationTypes) {

        final Class<? extends Annotation> actualAnnotationType = annotation.annotationType();
        if (knownCyclicAnnotationTypes.contains(actualAnnotationType)) {
            return false;
        }
        if (!visitedAnnotationTypes.add(actualAnnotationType)) {
            disallowedListAnnotation(actualAnnotationType);
            return false;
        }

        final Annotation[] metaAnnotations = annotation.annotationType().getDeclaredAnnotations();
        for (final Annotation metaAnnotation : metaAnnotations) {
            if (findMetaAnnotations(builder, metaAnnotation, annotationType, containerType,
                                    visitedAnnotationTypes)) {
                collectAnnotations(builder, metaAnnotation, annotationType, containerType);
            }
        }

        visitedAnnotationTypes.remove(actualAnnotationType);
        return true;
    }

    /**
     * Returns all annotations which are found from the following.
     * <ul>
     *     <li>the specified {@code element}</li>
     *     <li>the super classes of the specified {@code element} if the {@code element} is a class</li>
     *     <li>the meta-annotations of the annotations specified on the {@code element}
     *     or its super classes</li>
     * </ul>
     *
     * @param element the {@link AnnotatedElement} to find annotations
     */
    static List<Annotation> getAllAnnotations(AnnotatedElement element) {
        return getAnnotations(element, EnumSet.of(FindOption.LOOKUP_SUPER_CLASSES,
                                                  FindOption.LOOKUP_META_ANNOTATIONS));
    }

    /**
     * Returns all annotations searching from the specified {@code element}. The search range depends on
     * the specified {@link FindOption}s.
     *
     * @param element the {@link AnnotatedElement} to find annotations
     * @param findOptions the options to be applied when retrieving annotations
     */
    static List<Annotation> getAnnotations(AnnotatedElement element, FindOption... findOptions) {
        requireNonNull(findOptions, "findOptions");
        return getAnnotations(element,
                              findOptions.length > 0 ? EnumSet.copyOf(ImmutableList.copyOf(findOptions))
                                                     : EnumSet.noneOf(FindOption.class));
    }

    /**
     * Returns all annotations searching from the specified {@code element}. The search range depends on
     * the specified {@link FindOption}s.
     *
     * @param element the {@link AnnotatedElement} to find annotations
     * @param findOptions the options to be applied when retrieving annotations
     */
    static List<Annotation> getAnnotations(AnnotatedElement element, EnumSet<FindOption> findOptions) {
        return getAnnotations(element, findOptions, annotation -> true);
    }

    /**
     * Returns all annotations searching from the specified {@code element}. The search range depends on
     * the specified {@link FindOption}s and the specified {@code collectingFilter} decides whether
     * an annotation is collected or not.
     *
     * @param element the {@link AnnotatedElement} to find annotations
     * @param findOptions the options to be applied when retrieving annotations
     * @param collectingFilter the predicate which decides whether the annotation is to be collected or not
     */
    static List<Annotation> getAnnotations(AnnotatedElement element, EnumSet<FindOption> findOptions,
                                           Predicate<Annotation> collectingFilter) {
        requireNonNull(element, "element");
        requireNonNull(collectingFilter, "collectingFilter");

        final Builder<Annotation> builder = new Builder<>();

        for (final AnnotatedElement e : resolveTargetElements(element, findOptions)) {
            for (final Annotation annotation : e.getDeclaredAnnotations()) {
                if (findOptions.contains(FindOption.LOOKUP_META_ANNOTATIONS)) {
                    getMetaAnnotations(builder, annotation, collectingFilter);
                }
                if (collectingFilter.test(annotation)) {
                    builder.add(annotation);
                }
            }
        }
        return builder.build();
    }

    private static void getMetaAnnotations(Builder<Annotation> builder, Annotation annotation,
                                           Predicate<Annotation> collectingFilter) {
        getMetaAnnotations(builder, annotation, collectingFilter,
                           Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean getMetaAnnotations(Builder<Annotation> builder, Annotation annotation,
                                              Predicate<Annotation> collectingFilter,
                                              Set<Class<? extends Annotation>> visitedAnnotationTypes) {

        final Class<? extends Annotation> annotationType = annotation.annotationType();

        if (knownCyclicAnnotationTypes.contains(annotationType)) {
            return false;
        }
        if (!visitedAnnotationTypes.add(annotationType)) {
            disallowedListAnnotation(annotationType);
            return false;
        }

        final Annotation[] metaAnnotations = annotationType.getDeclaredAnnotations();
        for (final Annotation metaAnnotation : metaAnnotations) {

            if (getMetaAnnotations(builder, metaAnnotation, collectingFilter,
                                   visitedAnnotationTypes) &&
                collectingFilter.test(metaAnnotation)) {
                builder.add(metaAnnotation);
            }
        }

        visitedAnnotationTypes.remove(annotationType);
        return true;
    }

    private static void disallowedListAnnotation(Class<? extends Annotation> annotationType) {
        if (!knownCyclicAnnotationTypes.add(annotationType)) {
            return;
        }

        if (logger.isDebugEnabled()) {
            final String typeName = annotationType.getName();
            final Class<?>[] ifaces = annotationType.getInterfaces();
            final List<String> ifaceNames;
            if (ifaces.length != 0) {
                ifaceNames = Arrays.stream(ifaces)
                                   .filter(Class::isAnnotation)
                                   .map(Class::getName)
                                   .collect(toImmutableList());
            } else {
                ifaceNames = ImmutableList.of();
            }

            if (ifaceNames.isEmpty()) {
                logger.debug("Disallowed listing an annotation with a cyclic reference: {}", typeName);
            } else {
                logger.debug("Disallowed listing an annotation with a cyclic reference: {}{}", typeName, ifaceNames);
            }
        }
    }

    /**
     * Collects the list of {@link AnnotatedElement}s which are to be used to find annotations.
     */
    private static List<AnnotatedElement> resolveTargetElements(AnnotatedElement element,
                                                                EnumSet<FindOption> findOptions) {
        final List<AnnotatedElement> elements;
        if (findOptions.contains(FindOption.LOOKUP_SUPER_CLASSES) && element instanceof Class) {
            final Class<?> superclass = ((Class<?>) element).getSuperclass();
            if ((superclass == null || superclass == Object.class) &&
                ((Class<?>) element).getInterfaces().length == 0) {
                elements = ImmutableList.of(element);
            } else {
                final Builder<AnnotatedElement> collector = new Builder<>();
                collectSuperClasses((Class<?>) element, collector,
                                    findOptions.contains(FindOption.COLLECT_SUPER_CLASSES_FIRST));
                elements = collector.build();
            }
        } else {
            elements = ImmutableList.of(element);
        }
        return elements;
    }

    private static void collectSuperClasses(Class<?> clazz, Builder<AnnotatedElement> collector,
                                            boolean collectSuperClassesFirst) {
        final Class<?> superClass = clazz.getSuperclass();
        final Class<?>[] superInterfaces = clazz.getInterfaces();

        if (!collectSuperClassesFirst) {
            collector.add(clazz);
        }
        if (superInterfaces.length > 0) {
            Arrays.stream(superInterfaces)
                  .forEach(superInterface -> collectSuperClasses(
                          superInterface, collector, collectSuperClassesFirst));
        }
        if (superClass != null && superClass != Object.class) {
            collectSuperClasses(superClass, collector, collectSuperClassesFirst);
        }
        if (collectSuperClassesFirst) {
            collector.add(clazz);
        }
    }

    private static <T extends Annotation> void collectAnnotations(
            Builder<T> builder, Annotation annotation,
            Class<T> annotationType, @Nullable Class<? extends Annotation> containerType) {
        final Class<? extends Annotation> type = annotation.annotationType();
        if (type == annotationType) {
            builder.add(annotationType.cast(annotation));
            return;
        }

        if (containerType == null || type != containerType) {
            return;
        }

        // If this annotation is a containing annotation of the target annotation,
        // try to call "value" method of it in order to get annotations we are finding.
        final Method method = Iterables.getFirst(
                getMethods(containerType, withName("value"), withParametersCount(0)), null);
        if (method == null) {
            return;
        }

        method.setAccessible(true);

        try {
            @SuppressWarnings("unchecked")
            final T[] values = (T[]) method.invoke(annotation);
            Arrays.stream(values).forEach(builder::add);
        } catch (Exception e) {
            // Should not reach here.
            throw new Error("Failed to invoke 'value' method of the repeatable annotation: " +
                            containerType.getName(), e);
        }
    }

    private AnnotationUtil() {}
}
