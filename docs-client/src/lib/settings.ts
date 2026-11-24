/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

export interface DocServiceSettings {
  sensitiveHeaders: string[];
}

export const REDACTED_HEADER_VALUE = 'REDACTED';

export function redactSensitiveHeaders(
  headers: { [name: string]: string },
  sensitiveHeaders: string[],
): { [name: string]: string } {
  const sensitiveHeaderNames = new Set(
    sensitiveHeaders.map((headerName) => headerName.toLowerCase()),
  );
  return Object.fromEntries(
    Object.entries(headers).map(([name, value]) => [
      name,
      sensitiveHeaderNames.has(name.toLowerCase())
        ? REDACTED_HEADER_VALUE
        : value,
    ]),
  );
}
