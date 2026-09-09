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

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {
    private static Logger logger = LoggerFactory.getLogger(Main.class);
    public static void main(String[] args) throws Exception {
        OpenAPIParser.openAPI(parse(args));
    }

    private static boolean isFlagged(String val) {
        return val.startsWith("--");
    }

    private static Command parse(String[] args) {
        final Map<String, String> options = new HashMap<>();
        final String[] buffer = { null, null };
        String flagOption = null;
        int buffered = 0;

        for (int i = 0; i < args.length; ++i) {
            if (flagOption != null) {
                if (isFlagged(args[i])) {
                    System.out.println(String.format("There is not option value: %s", flagOption));
                    System.exit(1);
                } else {
                    options.put(flagOption, args[i]);
                    flagOption = null;
                }
            } else {
                if (isFlagged(args[i])) {
                    flagOption = args[i];
                } else {
                    if (buffered < 2) {
                        buffer[buffered++] = args[i];
                    }
                }
            }
        }

        if (buffer[0] == null ||  buffer[1] == null) {
            logger.warn("There is no required Option.");
            return null;
        }
        return new Command(buffer[0], buffer[1], options);
    }

    static class Command {
        private final String oasFilePath;
        private final String dstPath;
        private final Map<String, String> options;

        Command(String oasfilepath, String dstPath, Map<String, String> options) {
            dstPath = dstPath.endsWith("/") ? dstPath : dstPath + '/';
            this.dstPath = dstPath;
            this.oasFilePath = oasfilepath;
            this.options = options;
        }

        public String getOasFilePath() {
            return oasFilePath;
        }

        public String getDstPath() {
            return dstPath;
        }

        public Map<String, String> getOptions() {
            return options;
        }
    }

    private Main() {}
}
