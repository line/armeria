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

import javax.annotation.Nullable;

import org.apache.thrift.TBase;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

class ServiceInfo {

    private static final ObjectMapper mapper = new ObjectMapper();

    static ServiceInfo of(
            Class<?> serviceClass, Iterable<EndpointInfo> endpoints,
            Map<Class<?>, ? extends TBase<?, ?>> sampleRequests,
            Map<String, String> sampleHttpHeaders) throws ClassNotFoundException {
        requireNonNull(serviceClass, "serviceClass");

        final String name = serviceClass.getName();

        final ClassLoader serviceClassLoader = serviceClass.getClassLoader();
        final Class<?> interfaceClass = Class.forName(name + "$Iface", false, serviceClassLoader);
        final Method[] methods = interfaceClass.getDeclaredMethods();
        final Map<String, String> docStrings = ThriftDocString.getAllDocStrings(serviceClassLoader);

        final List<FunctionInfo> functions = new ArrayList<>(methods.length);
        final Set<ClassInfo> classes = new LinkedHashSet<>();
        for (Method method : methods) {
            final FunctionInfo function = FunctionInfo.of(method, sampleRequests, name, docStrings);
            functions.add(function);

            addClassIfPossible(classes, function.returnType());
            function.parameters().stream().forEach(p -> addClassIfPossible(classes, p.type()));
            function.exceptions().stream().forEach(e -> {
                e.fields().stream().forEach(f -> addClassIfPossible(classes, f.type()));
                addClassIfPossible(classes, e);
            });
        }

        String httpHeaders = "";
        if (sampleHttpHeaders != null) {
            try {
                httpHeaders = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sampleHttpHeaders);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Failed to serialize the given httpHeaders", e);
            }
        }

        return new ServiceInfo(name, functions, classes, endpoints, docStrings.get(name), httpHeaders);
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
    private final Map<String, EndpointInfo> endpoints;
    private final String docString;
    private final String sampleHttpHeaders;

    private ServiceInfo(String name,
                        List<FunctionInfo> functions,
                        Collection<ClassInfo> classes,
                        Iterable<EndpointInfo> endpoints,
                        @Nullable String docString,
                        @Nullable String sampleHttpHeaders) {

        this.name = requireNonNull(name, "name");

        requireNonNull(functions, "functions");
        requireNonNull(classes, "classes");
        requireNonNull(endpoints, "endpoints");

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

        final Map<String, EndpointInfo> endpoints0 = new TreeMap<>();
        for (EndpointInfo i : endpoints) {
            endpoints0.put(i.hostnamePattern() + ':' + i.path(), i);
        }
        this.endpoints = Collections.unmodifiableMap(endpoints0);

        this.docString = docString;
        this.sampleHttpHeaders = sampleHttpHeaders;
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

    @JsonProperty
    public Collection<EndpointInfo> endpoints() {
        return endpoints.values();
    }

    @JsonProperty
    public String docString() {
        return docString;
    }

    @JsonProperty
    public String sampleHttpHeaders() {
        return sampleHttpHeaders;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        ServiceInfo that = (ServiceInfo) o;
        return Objects.equals(name, that.name) &&
               Objects.equals(functions, that.functions) &&
               Objects.equals(classes, that.classes) &&
               Objects.equals(endpoints, that.endpoints);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, functions, classes, endpoints);
    }

    @Override
    public String toString() {
        return "ServiceInfo{" +
               "name='" + name() + '\'' +
               ", functions=" + functions() +
               ", classes=" + classes() +
               ", endpoints=" + endpoints() +
               ", docString=" + docString() +
               ", sampleHttpHeaders=" + sampleHttpHeaders() +
               '}';
    }
}
