/*
 * Copyright 2019 LINE Corporation
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
package com.lincorp.openapi;

import java.io.StringWriter;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;

public final class MustacheWriter {
    private static final MustacheFactory mf = new DefaultMustacheFactory();

    public static StringWriter write(String templateName, Object payload) {
        final StringWriter writer = new StringWriter();
        final Mustache mustache = mf.compile(templateName);
        return (StringWriter) mustache.execute(writer, payload);
    }

    private MustacheWriter() {}
}
