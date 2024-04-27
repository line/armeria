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

import Transport from './transport';
import { Method } from '../specification';
import { validateJsonObject } from '../json-util';

export const GRAPHQL_HTTP_MIME_TYPE = 'application/graphql+json';

export default class GraphqlHttpTransport extends Transport {
  public supportsMimeType(mimeType: string): boolean {
    return mimeType === GRAPHQL_HTTP_MIME_TYPE;
  }

  public getDebugMimeType(): string {
    return GRAPHQL_HTTP_MIME_TYPE;
  }

  protected async doSend(
    method: Method,
    headers: { [name: string]: string },
    pathPrefix: string,
    bodyJson?: string,
    endpointPath?: string,
    queries?: string,
  ): Promise<Response> {
    const endpoint = this.getDebugMimeTypeEndpoint(method);

    const hdrs = new Headers();
    hdrs.set('content-type', GRAPHQL_HTTP_MIME_TYPE);
    hdrs.set('accept', 'application/json');
    for (const [name, value] of Object.entries(headers)) {
      hdrs.set(name, value);
    }
    if (bodyJson && bodyJson.trim()) {
      validateJsonObject(bodyJson, 'request body');
    }

    let newPath =
      endpointPath || endpoint.pathMapping.substring('exact:'.length);
    if (queries && queries.length > 1) {
      newPath =
        newPath.indexOf('?') > 0
          ? `${newPath}&${queries}`
          : `${newPath}?${queries}`;
    }
    newPath = pathPrefix + newPath;

    return fetch(encodeURI(newPath), {
      headers: hdrs,
      method: method.httpMethod,
      body: bodyJson,
    });
  }
}
