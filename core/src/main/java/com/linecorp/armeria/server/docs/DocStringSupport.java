/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server.docs;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.server.docs.DocService.findSupportedServices;
import static com.linecorp.armeria.server.docs.DocService.plugins;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.linecorp.armeria.server.ServiceConfig;

final class DocStringSupport {

    private final Map<String, DescriptionInfo> descriptionInfos;

    DocStringSupport(List<ServiceConfig> services) {
        descriptionInfos =
                plugins.stream()
                       .flatMap(plugin -> plugin.loadDocStrings(findSupportedServices(plugin, services))
                                                .entrySet().stream())
                       .collect(toImmutableMap(Entry::getKey, Entry::getValue, (a, b) -> a));
    }

    ServiceSpecification addDocStrings(ServiceSpecification spec) {
        // Apply doc strings to enums, structs, and exceptions (which still have descriptionInfo)
        // Service, method, parameter, return, and throws descriptions are stored in docStrings map
        return new ServiceSpecification(
                spec.services(),
                spec.enums().stream().map(this::addEnumDocStrings).collect(toImmutableList()),
                spec.structs().stream().map(this::addStructDocStrings).collect(toImmutableList()),
                spec.exceptions().stream().map(this::addExceptionDocStrings).collect(toImmutableList()),
                spec.exampleHeaders(),
                descriptionInfos,
                spec.docServiceRoute());
    }

    private EnumInfo addEnumDocStrings(EnumInfo e) {
        final DescriptionInfo descriptionInfo = findDescription(e.name(), e.descriptionInfo());
        final List<EnumValueInfo> valuesWithDescription = e.values().stream()
                                                           .map(v -> addEnumValueDocString(e, v))
                                                           .collect(toImmutableList());

        return e.withValues(valuesWithDescription)
                .withDescriptionInfo(descriptionInfo);
    }

    private EnumValueInfo addEnumValueDocString(EnumInfo e, EnumValueInfo v) {
        final DescriptionInfo descriptionInfo =
                findDescription(e.name() + '/' + v.name(), v.descriptionInfo());
        return v.withDescriptionInfo(descriptionInfo);
    }

    private StructInfo addStructDocStrings(StructInfo struct) {
        final DescriptionInfo descriptionInfo = findDescription(struct.name(), struct.descriptionInfo());
        final List<FieldInfo> fieldsWithDescription = struct.fields().stream()
                                                            .map(field -> addFieldDocString(struct, field))
                                                            .collect(toImmutableList());

        return struct.withFields(fieldsWithDescription)
                     .withDescriptionInfo(descriptionInfo);
    }

    private ExceptionInfo addExceptionDocStrings(ExceptionInfo e) {
        final DescriptionInfo descriptionInfo = findDescription(e.name(), e.descriptionInfo());
        final List<FieldInfo> fieldsWithDescription = e.fields().stream()
                                                       .map(field -> addFieldDocString(e, field))
                                                       .collect(toImmutableList());

        return e.withFields(fieldsWithDescription)
                .withDescriptionInfo(descriptionInfo);
    }

    private FieldInfo addFieldDocString(DescriptiveTypeInfo parent, FieldInfo field) {
        final DescriptionInfo descriptionInfo =
                findDescription(parent.name() + '/' + field.name(), field.descriptionInfo());
        return field.withDescriptionInfo(descriptionInfo);
    }

    private DescriptionInfo findDescription(String key, DescriptionInfo currentDescriptionInfo) {
        if (currentDescriptionInfo != DescriptionInfo.empty()) {
            return currentDescriptionInfo;
        }

        return descriptionInfos.getOrDefault(key, DescriptionInfo.empty());
    }
}
