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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An object for xDS resources. This is a holder object which
 * is contained by {@link Snapshot} to:
 * <ul>
 *     <li>Provide additional metadata.</li>
 *     <li>Unify unpacking child object logic.</li>
 * </ul>
 */
@UnstableApi
public interface XdsResource {

    /**
     * Returns the xDS type of the object.
     */
    XdsType type();

    /**
     * Returns the resource.
     */
    Object resource();

    /**
     * Returns the resource name.
     */
    String name();
}
