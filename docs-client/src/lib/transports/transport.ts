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
import JSONbig from 'json-bigint';
import { jsonPrettify } from '../json-util';
import { docServiceDebug, providers } from '../header-provider';

import { Endpoint, Method } from '../specification';

export default abstract class Transport {
  public abstract supportsMimeType(mimeType: string): boolean;

  public abstract getDebugMimeType(): string;

  public async send(
    method: Method,
    headers: { [name: string]: string },
    pathPrefix: string,
    bodyJson?: string,
    endpointPath?: string,
    queries?: string,
  ): Promise<string> {
    const providedHeaders = await Promise.all(
      providers.map((provider) => provider()),
    );
    let filledHeaders = {};
    if (process.env.WEBPACK_DEV === 'true') {
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

    const httpResponse = await this.doSend(
      method,
      filledHeaders,
      pathPrefix,
      bodyJson,
      endpointPath,
      queries,
    );
    const responseText = await httpResponse.text();
    const applicationType = httpResponse.headers.get('content-type') || '';
    if (applicationType.indexOf('json') >= 0) {
      try {
        const json = JSONbig.parse(responseText);
        const prettified = jsonPrettify(JSONbig.stringify(json));
        if (prettified.length > 0) {
          return prettified;
        }
      } catch (e) {
        return responseText;
      }
    }

    if (responseText.length > 0) {
      return responseText;
    }

    return '<zero-length response>';
  }

  public findDebugMimeTypeEndpoint(
    method: Method,
    endpointPath?: string,
  ): Endpoint | undefined {
    return method.endpoints.find((ep) => {
      return (
        ep.availableMimeTypes.includes(this.getDebugMimeType()) &&
        (!endpointPath || !this.validatePath(ep, endpointPath).error)
      );
    });
  }

  public getDebugMimeTypeEndpoint(
    method: Method,
    endpointPath?: string,
  ): Endpoint {
    // Provide better error message to the UI if there is only one endpoint.
    const targetEndpoints = this.listDebugMimeTypeEndpoint(method);
    if (targetEndpoints.length === 1) {
      if (endpointPath) {
        const errorMsg = this.validatePath(
          targetEndpoints[0],
          endpointPath,
        ).error;
        if (errorMsg) {
          throw new Error(errorMsg);
        }
      }
      return targetEndpoints[0];
    }

    // General error message if not found.
    const endpoint = this.findDebugMimeTypeEndpoint(method, endpointPath);
    if (!endpoint) {
      if (endpointPath) {
        throw new Error(
          `Endpoint does not support debug transport. MimeType: ${this.getDebugMimeType()}, Supported paths: 
          ${targetEndpoints.map((ep) => ep.pathMapping).join()}`,
        );
      }
      throw new Error(
        `Endpoint does not support debug transport. MimeType: ${this.getDebugMimeType()}`,
      );
    }
    return endpoint;
  }

  public getCurlBody(
    _endpoint: Endpoint,
    _method: Method,
    body: string,
  ): string {
    return body;
  }

  public listDebugMimeTypeEndpoint(method: Method): Endpoint[] {
    return method.endpoints.filter((endpoint) =>
      endpoint.availableMimeTypes.includes(this.getDebugMimeType()),
    );
  }

  /**
   * Checking if the endpoint's path supports target path.
   * Default implementation is suitable for RPC, using endpoint.pathMapping === path.
   */
  protected validatePath(endpoint: Endpoint, path: string): { error?: string } {
    if (endpoint.pathMapping !== path) {
      return {
        error: `The path: '${path}' must be equal to ${endpoint.pathMapping}`,
      };
    }
    return {};
  }

  protected abstract doSend(
    method: Method,
    headers: { [name: string]: string },
    pathPrefix: string,
    bodyJson?: string,
    endpointPath?: string,
    queries?: string,
  ): Promise<Response>;
}
