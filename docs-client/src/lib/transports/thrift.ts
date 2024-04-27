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

import { Endpoint, Method } from '../specification';

import Transport from './transport';
import { validateJsonObject } from '../json-util';

export const TTEXT_MIME_TYPE = 'application/x-thrift; protocol=TTEXT';

export default class ThriftTransport extends Transport {
  private static thriftMethod(endpoint: Endpoint, method: Method) {
    return endpoint.fragment
      ? `${endpoint.fragment}:${method.name}`
      : method.name;
  }

  public supportsMimeType(mimeType: string): boolean {
    return mimeType === TTEXT_MIME_TYPE;
  }

  public getDebugMimeType(): string {
    return TTEXT_MIME_TYPE;
  }

  public getCurlBody(endpoint: Endpoint, method: Method, body: string): string {
    const thriftMethod = ThriftTransport.thriftMethod(endpoint, method);
    return `{"method":"${thriftMethod}", "type": "CALL", "args": ${body}}`;
  }

  protected async doSend(
    method: Method,
    headers: { [name: string]: string },
    pathPrefix: string,
    bodyJson?: string,
    endpointPath?: string,
  ): Promise<Response> {
    if (!bodyJson) {
      throw new Error('A Thrift request must have body.');
    }
    const endpoint = this.getDebugMimeTypeEndpoint(method, endpointPath);

    const thriftMethod = ThriftTransport.thriftMethod(endpoint, method);

    const hdrs = new Headers();
    hdrs.set('content-type', TTEXT_MIME_TYPE);
    for (const [name, value] of Object.entries(headers)) {
      hdrs.set(name, value);
    }
    if (bodyJson && bodyJson.trim()) {
      validateJsonObject(bodyJson, 'request body');
    }

    const newPath = pathPrefix + (endpointPath ?? endpoint.pathMapping);

    return fetch(newPath, {
      headers: hdrs,
      method: 'POST',
      body: `{"method": "${thriftMethod}", "type": "CALL", "args": ${bodyJson}}`,
    });
  }
}
