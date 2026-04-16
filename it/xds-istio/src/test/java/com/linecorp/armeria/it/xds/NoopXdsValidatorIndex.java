/*
 * Copyright 2025 LY Corporation
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
package com.linecorp.armeria.it.xds;

import com.linecorp.armeria.xds.validator.XdsValidatorIndex;

/**
 * A no-op {@link XdsValidatorIndex} that skips all protobuf validation.
 *
 * <p>Istio's gRPC proxyless mode ({@code GENERATOR: grpc}) produces xDS resources
 * with empty {@code stat_prefix} in {@code HttpConnectionManager}, which fails
 * the default pgv validation. This validator allows such resources to be processed.
 */
public class NoopXdsValidatorIndex implements XdsValidatorIndex {
    @Override
    public void assertValid(Object message) {
    }

    @Override
    public int priority() {
        return 1;
    }
}
