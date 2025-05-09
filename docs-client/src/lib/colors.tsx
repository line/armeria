/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

export const isInHexFormat = (color: string): boolean =>
  /^#([0-9a-fA-F]{6})$/.test(color);

export const getValidHexColor = (
  record: Record<string, string>,
  key: string,
): string | undefined => {
  const color = record[key];
  return color && isInHexFormat(color) ? color : undefined;
};
