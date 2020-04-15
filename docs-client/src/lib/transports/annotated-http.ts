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
import prettify from '../json-prettify';

import Transport from './transport';

export const ANNOTATED_HTTP_MIME_TYPE = 'application/json; charset=utf-8';

export default class AnnotatedHttpTransport extends Transport {
  public supportsMimeType(mimeType: string): boolean {
    return mimeType === ANNOTATED_HTTP_MIME_TYPE;
  }

  public getDebugMimeType(): string {
    return ANNOTATED_HTTP_MIME_TYPE;
  }

  protected async doSend(
    method: Method,
    headers: { [name: string]: string },
    bodyJson?: string,
    endpointPath?: string,
    queries?: string,
  ): Promise<string> {
    const endpoint = this.findDebugMimeTypeEndpoint(method);

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
    const response = await httpResponse.text();
    if (response.length > 0) {
      if (applicationType.indexOf('json') > -1) {
        const prettified = prettify(response);
        if (prettified.length === 0) {
          return response;
        }
        return prettified;
      }
      return response;
    }
    return '&lt;zero-length response&gt;';
  }
}
