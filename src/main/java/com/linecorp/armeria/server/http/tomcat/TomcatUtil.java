/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http.tomcat;

import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.catalina.LifecycleListener;
import org.apache.catalina.startup.Constants;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.Tomcat.DefaultWebXmlListener;

final class TomcatUtil {

    private static final LifecycleListener defaultWebXmlListener = new DefaultWebXmlListener();

    static URL getWebAppConfigFile(String contextPath, Path docBase) {
        final AtomicReference<URL> configUrlRef = new AtomicReference<>();
        new Tomcat() {{
            configUrlRef.set(getWebappConfigFile(docBase.toString(), contextPath));
        }};

        return configUrlRef.get();
    }

    static String noDefaultWebXmlPath() {
        return Constants.NoDefaultWebXml;
    }

    static LifecycleListener getDefaultWebXmlListener() {
        return defaultWebXmlListener;
    }

    static Class<?>[] classContext() {
        final AtomicReference<Class<?>[]> classContextRef = new AtomicReference<>();
        new SecurityManager() {{
            classContextRef.set(getClassContext());
        }};

        // Remove the first two classes which are:
        // - This class
        // - The anonymous SecurityManager
        final Class<?>[] classContext = classContextRef.get();
        return Arrays.copyOfRange(classContext, 2, classContext.length);
    }

    private TomcatUtil() {}
}
