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

/**
 * A pluggable provider of HTTP headers to issue with debug requests. The
 * headers will be added to the request before any headers present in the
 * debug form itself.
 */
export type HeaderProvider = () => Promise<{ [name: string]: string }>;

export const providers: HeaderProvider[] = [];

/**
 * Register a function that returns a {@link Promise} that resolves
 * headers to inject into debug requests. Accessible as
 * {@code window.armeria.registerHeaderProvider}.
 */
export function registerHeaderProvider(provider: HeaderProvider) {
  providers.push(provider);
}
