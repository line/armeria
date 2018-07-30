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

import { providers } from '../header-provider';
import { Method } from '../specification';

export default abstract class Transport {
  public async send(
    method: Method,
    bodyJson: string,
    headers: { [name: string]: string },
  ): Promise<string> {
    const providedHeaders = await Promise.all(
      providers.map((provider) => provider()),
    );
    let filledHeaders = {};
    for (const hdrs of providedHeaders) {
      filledHeaders = {
        ...filledHeaders,
        ...hdrs,
      };
    }
    filledHeaders = {
      ...filledHeaders,
      ...headers,
    };

    return this.doSend(method, bodyJson, filledHeaders);
  }

  public abstract supportsMimeType(mimeType: string): boolean;

  protected abstract doSend(
    method: Method,
    bodyJson: string,
    headers: { [name: string]: string },
  ): Promise<string>;
}
