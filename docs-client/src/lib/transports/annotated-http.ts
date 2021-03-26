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
import { Endpoint, Method } from '../specification';
import jsonPrettify from '../json-prettify';

import Transport from './transport';

export const ANNOTATED_HTTP_MIME_TYPE = 'application/json; charset=utf-8';

export default class AnnotatedHttpTransport extends Transport {
  public supportsMimeType(mimeType: string): boolean {
    return mimeType === ANNOTATED_HTTP_MIME_TYPE;
  }

  public getDebugMimeType(): string {
    return ANNOTATED_HTTP_MIME_TYPE;
  }

  protected validatePath(endpoint: Endpoint, path: string): { error?: string } {
    const regexPathPrefix = endpoint.regexPathPrefix;
    const originalPath = endpoint.pathMapping;

    if (originalPath.startsWith(`exact:`)) {
      const exact = originalPath.substring('exact:'.length);
      if (path !== exact) {
        return {
          error: `The path: '${path}' must be equal to: ${exact}`,
        };
      }
    }

    if (originalPath.startsWith('prefix:')) {
      // Prefix path mapping.
      const prefix = originalPath.substring('prefix:'.length);
      if (!path.startsWith(prefix)) {
        return {
          error: `The path: '${path}' should start with the prefix: ${prefix}`,
        };
      }
    }

    if (originalPath.startsWith('regex:')) {
      let regexPart;
      if (regexPathPrefix) {
        // Prefix adding path mapping.
        const prefix = regexPathPrefix.substring('prefix:'.length);
        if (!path.startsWith(prefix)) {
          return {
            error: `The path: '${path}' should start with the prefix: ${prefix}`,
          };
        }

        // Remove the prefix from the endpointPath so that we can test the regex.
        regexPart = path.substring(prefix.length - 1);
      } else {
        regexPart = path;
      }
      const regExp = new RegExp(originalPath.substring('regex:'.length));
      if (!regExp.test(regexPart)) {
        const expectedPath = regexPathPrefix
          ? `${regexPathPrefix} ${originalPath}`
          : originalPath;
        return {
          error: `Endpoint path: ${path} (expected: ${expectedPath})`,
        };
      }
    }
    return {};
  }

  protected async doSend(
    method: Method,
    headers: { [name: string]: string },
    bodyJson?: string,
    endpointPath?: string,
    queries?: string,
  ): Promise<string> {
    const endpoint = this.getDebugMimeTypeEndpoint(method);

    const hdrs = new Headers();
    hdrs.set('content-type', ANNOTATED_HTTP_MIME_TYPE);
    for (const [name, value] of Object.entries(headers)) {
      hdrs.set(name, value);
    }

    let newPath =
      endpointPath || endpoint.pathMapping.substring('exact:'.length);
    if (queries && queries.length > 1) {
      newPath =
        newPath.indexOf('?') > 0
          ? `${newPath}&${queries}`
          : `${newPath}?${queries}`;
    }

    const httpResponse = await fetch(encodeURI(newPath), {
      headers: hdrs,
      method: method.httpMethod,
      body: bodyJson,
    });
    const applicationType = httpResponse.headers.get('content-type') || '';
    if (applicationType.indexOf('json') >= 0) {
      const json = JSONbig.parse(await httpResponse.text());
      const prettified = jsonPrettify(JSONbig.stringify(json));
      if (prettified.length > 0) {
        return prettified;
      }
    }

    const responseText = await httpResponse.text();
    if (responseText.length > 0) {
      return responseText;
    }
    return '<zero-length response>';
  }
}
