/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.xds.filter.athenz;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.XdsTypeRegistryPackageProvider;

/**
 * Provides the Athenz protobuf package for xDS type registry discovery.
 */
@UnstableApi
public final class AthenzTypeRegistryPackageProvider implements XdsTypeRegistryPackageProvider {

    @Override
    public Iterable<String> packages() {
        return ImmutableList.of("jp.co.lycorp.ftd.athenz.v1", "com.linecorp.armeria.xds.athenz");
    }
}
