/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.server.servlet.util;

/**
 * Http header constants.
 */
public abstract class HttpHeaderConstants {
    public static final int HTTPS_PORT = 443;
    public static final int HTTP_PORT = 80;
    public static final String POST = "POST";
    public static final String HTTPS = "https";
    public static final String HTTP = "http";
    public static final CharSequence CHARSET = "charset";
}
