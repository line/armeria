/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.common.logback;

import com.google.common.base.MoreObjects;

import io.netty.util.AttributeKey;

public class CustomObject {

    final String name;
    final String value;

    public CustomObject(String name, String value) {
        this.name = name;
        this.value = value;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name)
                          .add("value", value).toString();
    }

    public static final AttributeKey<CustomObject> ATTR = AttributeKey.valueOf(CustomObject.class, "ATTR");

    public static final AttributeKey<String> FOO = AttributeKey.valueOf(CustomObject.class, "FOO");
}
