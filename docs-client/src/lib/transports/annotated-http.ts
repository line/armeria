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

import { Method } from '../specification';

import Transport from './transport';

const JSON_MIME_TYPE = 'application/json; charset=utf-8';

export default class AnnotatedHttpTransport extends Transport {
  private static newPath(
    endpointPath: string,
    bodyJson: string,
    queries: string | undefined,
  ): string {
    let newPath = endpointPath;
    const parsed = JSON.parse(bodyJson);
    const findingPathParamRegex = /(?:\{\w+\})/g;
    let match = findingPathParamRegex.exec(newPath);
    while (match != null) {
      const pathParam = match[0].substring(1, match[0].length - 1); // Remove '{' and '}'.
      const pathParamValue = parsed[pathParam];
      if (pathParamValue == null) {
        throw new Error(`The body should contain path parameter: ${pathParam}`);
      }
      newPath = newPath.replace(match[0], pathParamValue);
      match = findingPathParamRegex.exec(newPath);
    }
    if (queries && queries.length > 1) {
      if (queries.charAt(0) === '?') {
        newPath += queries;
      } else {
        newPath += '?' + queries;
      }
    }
    return newPath;
  }

  public supportsMimeType(mimeType: string): boolean {
    return mimeType === JSON_MIME_TYPE;
  }

  protected async doSend(
    method: Method,
    bodyJson: string,
    headers: { [name: string]: string },
    endpointPath?: string,
    queries?: string,
  ): Promise<string> {
    const endpoint = method.endpoints.find((ep) =>
      ep.availableMimeTypes.includes(JSON_MIME_TYPE),
    );
    if (!endpoint) {
      throw new Error(
        'Endpoint does not support annotated HTTP debug transport',
      );
    }

    const hdrs = new Headers();
    hdrs.set('content-type', JSON_MIME_TYPE);
    for (const [name, value] of Object.entries(headers)) {
      hdrs.set(name, value);
    }

    const newPath = encodeURI(
      endpointPath
        ? endpointPath
        : AnnotatedHttpTransport.newPath(endpoint.path, bodyJson, queries),
    );
    const sendBody =
      method.httpMethod === 'GET' || method.httpMethod === 'HEAD'
        ? null
        : bodyJson;
    const httpResponse = await fetch(newPath, {
      headers: hdrs,
      method: method.httpMethod,
      body: sendBody,
    });
    const response = await httpResponse.text();
    return response.length > 0 ? response : 'No response really?';
  }
}
