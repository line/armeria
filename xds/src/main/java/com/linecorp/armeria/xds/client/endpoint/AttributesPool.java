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

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.Attributes;
import com.linecorp.armeria.internal.client.endpoint.EndpointAttributeKeys;

import io.netty.util.AttributeKey;

final class AttributesPool {

    static final AttributesPool NOOP = new AttributesPool();

    private Map<Endpoint, Attributes> cachedAttrs = ImmutableMap.of();

    AttributesPool() {}

    AttributesPool(AttributesPool other) {
        cachedAttrs = ImmutableMap.copyOf(other.cachedAttrs);
    }

    List<Endpoint> cacheAttributesAndDelegate(List<Endpoint> endpoints) {
        final long defaultTimestamp = System.nanoTime();
        final ImmutableList.Builder<Endpoint> endpointsBuilder = ImmutableList.builder();
        final ImmutableMap.Builder<Endpoint, Attributes> prevAttrsBuilder = ImmutableMap.builder();
        for (Endpoint endpoint: endpoints) {
            // attach attributes
            endpoint = attachAttribute(endpoint, EndpointAttributeKeys.CREATED_AT_NANOS_KEY, defaultTimestamp);

            endpointsBuilder.add(endpoint);
            prevAttrsBuilder.put(endpoint, endpoint.attrs());
        }
        cachedAttrs = prevAttrsBuilder.buildKeepingLast();
        return endpointsBuilder.build();
    }

    private <T> Endpoint attachAttribute(Endpoint endpoint, AttributeKey<T> attrKey, T defaultValue) {
        if (endpoint.attr(attrKey) != null) {
            return endpoint;
        }
        T attrVal = null;
        final Attributes prevAttr = cachedAttrs.get(endpoint);
        if (prevAttr != null) {
            attrVal = prevAttr.attr(attrKey);
        }
        return endpoint.withAttr(attrKey, attrVal != null ? attrVal : defaultValue);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("cachedAttrs", cachedAttrs)
                          .toString();
    }
}
