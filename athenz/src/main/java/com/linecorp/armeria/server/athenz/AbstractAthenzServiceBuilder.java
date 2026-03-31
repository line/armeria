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

package com.linecorp.armeria.server.athenz;

import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A base builder for creating an Athenz service that checks access permissions using Athenz policies.
 */
@UnstableApi
public abstract class AbstractAthenzServiceBuilder<SELF extends AbstractAthenzServiceBuilder<SELF>>
        extends AbstractAthenzAuthorizerBuilder<SELF> {

    AbstractAthenzServiceBuilder(ZtsBaseClient ztsBaseClient) {
        super(ztsBaseClient);
    }

    // Keep this class since we may add APIs in the future.
}
