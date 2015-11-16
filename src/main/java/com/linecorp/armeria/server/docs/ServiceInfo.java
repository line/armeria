/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.docs;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonProperty;

class ServiceInfo {

    static ServiceInfo of(Class<?> serviceClass) throws ClassNotFoundException {
        requireNonNull(serviceClass, "serviceClass");

        final String name = serviceClass.getName();

        final Class<?> interfaceClass = Class.forName(name + "$Iface", false, serviceClass.getClassLoader());
        final Method[] methods = interfaceClass.getDeclaredMethods();

        final List<FunctionInfo> functions = new ArrayList<>(methods.length);
        final Set<ClassInfo> classes = new LinkedHashSet<>();
        for (Method method : methods) {
            final FunctionInfo function = FunctionInfo.of(method);
            functions.add(function);

            addClassIfPossible(classes, function.returnType());
            function.parameters().stream().forEach(p -> addClassIfPossible(classes, p.type()));
            function.exceptions().stream().forEach(e -> {
                e.fields().stream().forEach(f -> addClassIfPossible(classes, f.type()));
                addClassIfPossible(classes, e);
            });
        }

        return new ServiceInfo(name, functions, classes);
    }

    private static void addClassIfPossible(Set<ClassInfo> classes, TypeInfo typeInfo) {
        if (typeInfo instanceof ClassInfo) {
            final ClassInfo classInfo = (ClassInfo) typeInfo;
            classInfo.fields().stream().forEach(f -> addClassIfPossible(classes, f.type()));
            classes.add(classInfo);
        } else if (typeInfo instanceof CollectionInfo) {
            addClassIfPossible(classes, ((CollectionInfo) typeInfo).elementType());
        } else if (typeInfo instanceof MapInfo) {
            final MapInfo mapInfo = (MapInfo) typeInfo;
            addClassIfPossible(classes, mapInfo.keyType());
            addClassIfPossible(classes, mapInfo.valueType());
        }
    }

    private final String name;
    private final Map<String, FunctionInfo> functions;
    private final Map<String, ClassInfo> classes;

    private ServiceInfo(String name, List<FunctionInfo> functions, Collection<ClassInfo> classes) {
        this.name = requireNonNull(name, "name");

        requireNonNull(functions, "functions");
        requireNonNull(classes, "classes");

        final Map<String, FunctionInfo> functions0 = new TreeMap<>();
        for (FunctionInfo function : functions) {
            functions0.put(function.name(), function);
        }
        this.functions = Collections.unmodifiableMap(functions0);

        final Map<String, ClassInfo> classes0 = new TreeMap<>();
        for (ClassInfo classInfo : classes) {
            classes0.put(classInfo.name(), classInfo);
        }
        this.classes = Collections.unmodifiableMap(classes0);
    }

    @JsonProperty
    public String name() {
        return name;
    }

    @JsonProperty
    public String simpleName() {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    @JsonProperty
    public Map<String, FunctionInfo> functions() {
        return functions;
    }

    @JsonProperty
    public Map<String, ClassInfo> classes() {
        return classes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        ServiceInfo that = (ServiceInfo) o;
        return Objects.equals(name, that.name) &&
               Objects.equals(functions, that.functions) &&
               Objects.equals(classes, that.classes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, functions, classes);
    }

    @Override
    public String toString() {
        return "ServiceInfo{" +
               "name='" + name + '\'' +
               ", functions=" + functions +
               ", classes=" + classes +
               '}';
    }
}
