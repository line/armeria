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
package com.lincorp.openapi.generator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lincorp.openapi.MustacheWriter;

import io.swagger.v3.oas.models.OpenAPI;

public final class Generator {
    private static final Logger logger = LoggerFactory.getLogger(Generator.class);

    public static void armeria(OpenAPI openAPI, String destPath, Map<String, String> options)
            throws IOException {
        final Map<String, String> comment = new HashMap<String, String>() {
            {
                put("version", openAPI.getOpenapi());
                put("applicationName", openAPI.getInfo().getTitle());
                put("docVersion", openAPI.getInfo().getVersion());
            }};

        final Map<String, Map<String, Object>> classMap = new HashMap<>();

        openAPI.getPaths()
               .forEach((key1, value1) -> value1
                       .readOperationsMap()
                       .forEach((key, value) -> {
                           final String[] tokens = key1.split("/");
                           final String className = tokens.length <= 1 ?
                                                    "AnnotatedService" : startWithUppercase(tokens[1]);

                           final String httpMethod = startWithUppercase(
                                   key.name().toLowerCase());
                           final String methodName = value.getOperationId();

                           if (classMap.containsKey(className)) {
                               final Map<String, Object> api = classMap.get(className);
                               final Set<String> imports = (Set<String>) api.get("import");

                               final List<Map<String, String>> methods =
                                       (List<Map<String, String>>) api.get("methods");

                               imports.add(httpMethod);
                               methods.add(new HashMap<String, String>() {
                                   {
                                               put("methodName", methodName);
                                               put("path", key1);
                                               put("httpMethod", httpMethod);
                                   }
                               });
                           } else {
                               final Map<String, Object> api = new HashMap<String, Object>() {{
                                   put("className", className);
                                   put("comment", comment);
                                   put("import", new HashSet() {
                                       { add(httpMethod); }
                                   });
                                   put("methods", new ArrayList<Map<String, String>>() {
                                       {
                                           add(new HashMap() {
                                               {
                                               put("methodName", methodName);
                                               put("path", key1);
                                               put("httpMethod", httpMethod);
                                               }
                                           });
                                       }
                                   });
                               }};

                               classMap.put(className, api);
                           }
                       }));
        classMap.entrySet().forEach(entry -> {
            try {
                genClassFile(destPath + startWithUppercase(entry.getKey()) + ".java", entry.getValue());
            } catch (IOException e) {
                logger.error("Fail to Write File:", e);
            }
        });
    }

    private static String startWithUppercase(String str) {
        return str.toUpperCase().charAt(0) + str.substring(1);
    }

    private static void genClassFile(String destPath, Map<String, Object> apiObject) throws IOException {
        final File dest = new File(destPath);
        FileUtils.touch(dest);

        try (FileOutputStream outputStream = new FileOutputStream(dest)) {
            outputStream.write(MustacheWriter.write("class.mustache", apiObject).toString().getBytes());
        }
    }

    private Generator() {}
}
