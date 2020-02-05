/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.internal.server.tomcat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.catalina.util.ServerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TomcatVersion {

    private static final Logger logger = LoggerFactory.getLogger(TomcatVersion.class);

    private static final int TOMCAT_MAJOR_VERSION;
    private static final int TOMCAT_MINOR_VERSION;

    static {
        final Pattern pattern = Pattern.compile("^([1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.");
        final String version = ServerInfo.getServerNumber();
        final Matcher matcher = pattern.matcher(version);
        int tomcatMajorVersion = -1;
        int tomcatMinorVersion = -1;
        if (matcher.find()) {
            try {
                tomcatMajorVersion = Integer.parseInt(matcher.group(1));
                tomcatMinorVersion = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException ignored) {
                // Probably greater than Integer.MAX_VALUE
            }
        }

        TOMCAT_MAJOR_VERSION = tomcatMajorVersion;
        TOMCAT_MINOR_VERSION = tomcatMinorVersion;
        if (TOMCAT_MAJOR_VERSION > 0) {
            logger.info("Tomcat version: {} ({}.{})", version, TOMCAT_MAJOR_VERSION, TOMCAT_MINOR_VERSION);
        } else {
            logger.info("Tomcat version: {} (unknown)", version);
        }
    }

    public static int major() {
        return TOMCAT_MAJOR_VERSION;
    }

    public static int minor() {
        return TOMCAT_MINOR_VERSION;
    }

    private TomcatVersion() {}
}
