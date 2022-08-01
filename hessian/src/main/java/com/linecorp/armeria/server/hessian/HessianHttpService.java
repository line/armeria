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

package com.linecorp.armeria.server.hessian;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.HttpServiceWithRoutes;

/**
 * hessian http service.
 *
 * @author eisig
 */
@UnstableApi
public interface HessianHttpService extends HttpServiceWithRoutes {

    /**
     * Creates a new instance of {@link HessianHttpServiceBuilder} which can build an
     * instance of {@link HessianHttpService} fluently.
     *
     * <p>Currently, the only way to specify a serialization format is by using the HTTP
     * session protocol and setting the {@code "Content-Type"} header to the appropriate
     * {@link SerializationFormat#mediaType()}.
     */
    static HessianHttpServiceBuilder builder() {
        return new HessianHttpServiceBuilder();
    }
}
