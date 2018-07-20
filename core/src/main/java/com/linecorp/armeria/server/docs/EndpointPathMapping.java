/*
 *  Copyright 2018 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server.docs;

import com.linecorp.armeria.server.AnnotatedHttpService;

/**
 * The path mapping of an endpoint in {@link EndpointInfo}.
 * This information is used when a user send a debug request to {@link AnnotatedHttpService}
 * in the {@link DocService}.
 */
public enum EndpointPathMapping {

    /**
     * If the mapping is default, users cannot specify the endpoint path.
     */
    DEFAULT,

    /**
     * If the mapping is prefix, users can specify the endpoint path following by the prefix path.
     */
    PREFIX,

    /**
     * If the mapping is regex, users have to specify the endpoint path that matches the regex pattern.
     */
    REGEX,
}
