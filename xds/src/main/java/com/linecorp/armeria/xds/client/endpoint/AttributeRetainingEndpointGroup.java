/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Attributes;

import io.netty.util.AttributeKey;

final class AttributeRetainingEndpointGroup extends DynamicEndpointGroup implements Consumer<List<Endpoint>> {

    private final Map<Endpoint, Attributes> prevAttrsMap;
    private final List<AttributeKey<Object>> interestedKeys;

    @SuppressWarnings("unchecked")
    AttributeRetainingEndpointGroup(EndpointGroup delegate, Map<Endpoint, Attributes> prevAttrsMap,
                                    AttributeKey<?>... interestedKeys) {
        this.prevAttrsMap = prevAttrsMap;
        this.interestedKeys = ImmutableList.copyOf((AttributeKey<Object>[]) interestedKeys);
        delegate.addListener(this, true);
    }

    @Override
    public void accept(List<Endpoint> endpoints) {
        final ImmutableList.Builder<Endpoint> endpointsBuilder = ImmutableList.builder();
        for (Endpoint endpoint : endpoints) {
            final Attributes prevAttrs = prevAttrsMap.get(endpoint);
            if (prevAttrs == null) {
                endpointsBuilder.add(endpoint);
                continue;
            }
            for (AttributeKey<Object> key : interestedKeys) {
                if (endpoint.attr(key) == null) {
                    endpoint = endpoint.withAttr(key, prevAttrs.attr(key));
                }
            }
            endpointsBuilder.add(endpoint);
        }
        setEndpoints(endpointsBuilder.build());
    }
}
