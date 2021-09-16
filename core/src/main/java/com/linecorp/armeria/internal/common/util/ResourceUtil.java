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

package com.linecorp.armeria.internal.common.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * A utility class that helps to resolve resource locations in the file system.
 */
public final class ResourceUtil {

    /**
     * Resolves the given resource location to a {@link URL}.
     *
     * <p>Does not check whether the URL actually exists; simply returns
     * the URL that the given location would correspond to.
     *
     * @param resourceLocation the resource location to resolve: either a
     *                         "classpath:" pseudo URL, a "file:" URL, or a plain file path
     * @return a corresponding URL object
     * @throws FileNotFoundException if the resource cannot be resolved to a URL
     */
    public static URL getURL(String resourceLocation) throws FileNotFoundException {
        // Forked from Spring 5.2.2
        // https://github.com/spring-projects/spring-framework/blob/22a888b53df620b0905ce8beb6b0cf71981086d8/spring-core/src/main/java/org/springframework/util/ResourceUtils.java#L129
        if (resourceLocation.startsWith("classpath:")) {
            final String path = resourceLocation.substring("classpath:".length());
            final URL resource = ResourceUtil.class.getClassLoader().getResource(path);
            if (resource == null) {
                throw new FileNotFoundException("class path resource [" + path +
                                                "] cannot be resolved to URL because it does not exist");
            }
            return resource;
        }
        try {
            // try URL
            return new URL(resourceLocation);
        } catch (MalformedURLException ex) {
            // no URL -> treat as file path
            try {
                return new File(resourceLocation).toURI().toURL();
            } catch (MalformedURLException ex2) {
                throw new FileNotFoundException("Resource location [" + resourceLocation +
                                                "] is neither a URL nor a well-formed file path");
            }
        }
    }

    private ResourceUtil() {}
}
