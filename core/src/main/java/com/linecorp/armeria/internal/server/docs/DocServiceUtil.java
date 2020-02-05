/*
 *  Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.internal.server.docs;

import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceFilter;

/**
 * A utility class which provides useful methods for using in {@link DocService}.
 */
public final class DocServiceUtil {

    public static DocServiceFilter unifyFilter(DocServiceFilter includeFilter, DocServiceFilter excludeFilter) {
        return (pluginName, serviceName, methodName) ->
                includeFilter.test(pluginName, serviceName, methodName) &&
                !excludeFilter.test(pluginName, serviceName, methodName);
    }

    private DocServiceUtil() {}
}
