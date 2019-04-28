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

import { docServiceDebug, providers } from '../header-provider';

import { Endpoint, Method } from '../specification';

export default abstract class Transport {
  public async send(
    method: Method,
    headers: { [name: string]: string },
    bodyJson?: string,
    endpointPath?: string,
    queries?: string,
  ): Promise<string> {
    const providedHeaders = await Promise.all(
      providers.map((provider) => provider()),
    );
    let filledHeaders = {};
    if (process.env.WEBPACK_SERVE === 'true') {
      filledHeaders = { [docServiceDebug]: 'true' };
    }

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
    return this.doSend(method, filledHeaders, bodyJson, endpointPath, queries);
  }

  public curlPathAndBody(method: Method, body: string): [string, string] {
    const endpoint = this.findDebugMimeTypeEndpoint(method);
    if (!endpoint) {
      throw new Error("Endpoint null");
    }

    return [this.getCurlPath(endpoint), this.getCurlBody(body, endpoint, method)];
  }

  protected findDebugMimeTypeEndpoint(method: Method): Endpoint {
    const endpoint = method.endpoints.find((ep) =>
        ep.availableMimeTypes.includes(this.getDebugMimeType()),
    );
    if (!endpoint) {
      throw new Error('Endpoint does not support debug transport.');
    }
    return endpoint;
  }

  protected getCurlPath(endpoint: Endpoint): string {
    return endpoint.pathMapping;
  }

  protected getCurlBody(body: string, _endpoint: Endpoint, _method: Method): string {
    return body;
  }

  public abstract supportsMimeType(mimeType: string): boolean;

  public abstract getDebugMimeType(): string;

  protected abstract doSend(
    method: Method,
    headers: { [name: string]: string },
    bodyJson?: string,
    endpointPath?: string,
    queries?: string,
  ): Promise<string>;
}
