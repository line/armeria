/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.xds;

import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;

abstract class ResourceParser<I extends Message, O extends XdsResource> {

    @Nullable
    abstract String name(I message);

    abstract Class<I> clazz();

    abstract O parse(I message);

    ParsedResourcesHolder<O> parseResources(List<Any> resources) {
        final ImmutableMap.Builder<String, O> parsedResources = ImmutableMap.builder();
        final ImmutableSet.Builder<String> invalidResources = ImmutableSet.builder();
        final ImmutableList.Builder<String> errors = ImmutableList.builder();

        for (int i = 0; i < resources.size(); i++) {
            final Any resource = resources.get(i);

            final I unpackedMessage;
            try {
                unpackedMessage = resource.unpack(clazz());
            } catch (InvalidProtocolBufferException e) {
                errors.add(String.format("Resource (%s: %s) cannot be unpacked to (%s) due to %s",
                                         i, resource, clazz().getSimpleName(), e));
                continue;
            }
            final String name;
            try {
                name = name(unpackedMessage);
            } catch (Exception e) {
                errors.add(String.format("Cannot determine name of (%s: %s) with type %s due to %s",
                                         i, resource, clazz().getSimpleName(), e));
                continue;
            }

            if (name == null) {
                errors.add(String.format("Resource (%s: %s) cannot be processed as (%s) due to null name",
                                         i, resource, clazz().getSimpleName()));
                continue;
            }

            final O resourceUpdate;
            try {
                resourceUpdate = parse(unpackedMessage);
            } catch (Exception e) {
                errors.add(String.format("Resource (%s: %s) cannot be parsed as (%s) due to %s",
                                         i, resource, clazz().getSimpleName(), e));
                invalidResources.add(name);
                continue;
            }

            // Resource parsed successfully.
            parsedResources.put(name, resourceUpdate);
        }

        return new ParsedResourcesHolder<>(parsedResources.build(), invalidResources.build(), errors.build());
    }

    // Do not confuse with the SotW approach: it is the mechanism in which the client must specify all
    // resource names it is interested in with each request. Different resource types may behave
    // differently in this approach. For LDS and CDS resources, the server must return all resources
    // that the client has subscribed to in each request. For RDS and EDS, the server may only return
    // the resources that need an update.
    abstract boolean isFullStateOfTheWorld();

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(clazz()).toString();
    }

    abstract XdsType type();
}
