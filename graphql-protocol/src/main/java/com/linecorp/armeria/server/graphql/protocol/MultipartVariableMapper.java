/*
 * Copyright 2022 LINE Corporation
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
/*
 * Copyright 2021 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.server.graphql.protocol;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Mapper for handling populating the query variables with the files specified by object paths
 * in the multi-part request.
 */
@UnstableApi
final class MultipartVariableMapper {

   // Forked form https://github.com/Netflix/dgs-framework/blob/12cc1931e3c410e342134b7c0a8401f2c5a4aca6/graphql-dgs/src/main/kotlin/com/netflix/graphql/dgs/internal/utils/MultipartVariableMapper.kt

   private static final Pattern PERIOD = Pattern.compile("\\.");
   private static final Mapper<Map<String, Object>> MAP_MAPPER = new Mapper<Map<String, Object>>() {
      @Override
      public Object set(Map<String, Object> location, String target, Path path) {
         return location.put(target, path);
      }

      @Override
      public Object recurse(Map<String, Object> location, String target) {
         return location.get(target);
      }
   };
   private static final Mapper<List<Object>> LIST_MAPPER = new Mapper<List<Object>>() {
      @Override
      public Object set(List<Object> location, String target, Path path) {
         final Integer value = parseUnsignedInt(target);
         if (value == null) {
            return location;
         }
         if (value >= location.size()) {
            return location;
         }
         return location.set(value, path);
      }

      @Override
      public Object recurse(List<Object> location, String target) {
         final Integer value = parseUnsignedInt(target);
         if (value == null) {
            return null;
         }
         if (value >= location.size()) {
            return null;
         }
         return location.get(value);
      }
   };

   static void mapVariable(String objectPath, Map<String, Object> variables, Path path) {
      final String[] segments = PERIOD.split(objectPath);
      if (segments.length < 2) {
         throw new IllegalArgumentException("object-path in map must have at least two segments");
      }
      if (!"variables".equals(segments[0])) {
         throw new IllegalArgumentException("can only map into variables");
      }

      Object currentLocation = variables;
      for (int i = 1; i < segments.length; i++) {
         final String segmentName = segments[i];
         if (i == segments.length - 1) {
            if (currentLocation instanceof Map) {
               if (MAP_MAPPER.set((Map<String, Object>) currentLocation, segmentName, path) != null) {
                  throw new IllegalArgumentException("expected null value when mapping " + objectPath);
               }
            } else {
               if (LIST_MAPPER.set((List<Object>) currentLocation, segmentName, path) != null) {
                  throw new IllegalArgumentException("expected null value when mapping " + objectPath);
               }
            }
         } else {
            if (currentLocation instanceof Map) {
               currentLocation = MAP_MAPPER.recurse((Map<String, Object>) currentLocation, segmentName);
            } else {
               currentLocation = LIST_MAPPER.recurse((List<Object>) currentLocation, segmentName);
            }
            if (currentLocation == null) {
               throw new IllegalArgumentException(
                       "found null intermediate value when trying to map " + objectPath);
            }
         }
      }
   }

   interface Mapper<T> {
      @Nullable
      Object set(T location, String target, Path path);

      @Nullable
      Object recurse(T location, String target);
   }

   @Nullable
   private static Integer parseUnsignedInt(String value) {
      try {
         final int parseInt = Integer.parseInt(value);
         if (parseInt < 0) {
            return null;
         }
         return parseInt;
      } catch (NumberFormatException ignored) {
         return null;
      }
   }

   private MultipartVariableMapper() {}
}
