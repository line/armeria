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
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.Message;

abstract class ResourceParser<I extends Message, O extends XdsResource> {

    abstract String name(I message);

    abstract Class<I> clazz();

    abstract O parse(I message, String version, long revision);

    ParsedResourcesHolder parseResources(List<Any> resources, String version, long revision) {
        final ImmutableMap.Builder<String, Object> parsedResources = ImmutableMap.builder();
        final ImmutableMap.Builder<String, Throwable> invalidResources = ImmutableMap.builder();

        for (int i = 0; i < resources.size(); i++) {
            final Any resource = resources.get(i);

            final I unpackedMessage;
            try {
                unpackedMessage = resource.unpack(clazz());
            } catch (Exception e) {
                final String genName = String.format("generated_%s_%s", i, clazz().getSimpleName());
                invalidResources.put(genName,e);
                continue;
            }
            final String name = name(unpackedMessage);
            final O resourceUpdate;
            try {
                resourceUpdate = parse(unpackedMessage, version, revision);
            } catch (Exception e) {
                invalidResources.put(name, e);
                continue;
            }

            // Resource parsed successfully.
            parsedResources.put(name, resourceUpdate);
        }

        return new ParsedResourcesHolder(parsedResources.buildKeepingLast(),
                                         invalidResources.buildKeepingLast());
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
