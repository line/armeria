/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.client.hessian;

import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * {@link ClientOption}s to control hessian-specific behavior.
 */
@UnstableApi
public final class HessianClientOptions {

    /**
     * Enable hessian method overload. The default value is false
     */
    public static final ClientOption<Boolean> OVERLOAD_ENABLED = ClientOption.define("HESSIAN_OVERLOAD_ENABLED",
                                                                                     Boolean.FALSE);

    /**
     * Use hessian2 request. The default value is true.
     */
    public static final ClientOption<Boolean> HESSIAN2_REQUEST = ClientOption.define("HESSIAN_HESSIAN2_REQUEST",
                                                                                     Boolean.TRUE);

    /**
     * Use hessian2 reply. The default value is true.
     *
     */
    public static final ClientOption<Boolean> HESSIAN2_REPLY = ClientOption.define("HESSIAN_HESSIAN2_REPLY",
                                                                                   Boolean.TRUE);

    /**
     * enable hessian2 debug . The default value is false.
     */
    public static final ClientOption<Boolean> DEBUG = ClientOption.define("HESSIAN_DEBUG", Boolean.FALSE);

    private HessianClientOptions() {
    }
}
