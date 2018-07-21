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

const TTEXT_MIME_TYPE = 'application/x-thrift; protocol=TTEXT';

export default class ThriftTransport extends Transport {
  public supportsMimeType(mimeType: string): boolean {
    return mimeType === TTEXT_MIME_TYPE;
  }

  protected async doSend(
    method: Method,
    bodyJson: string,
    headers: { [name: string]: string },
  ): Promise<string> {
    const endpoint = method.endpoints.find((ep) =>
      ep.availableMimeTypes.includes(TTEXT_MIME_TYPE),
    );
    if (!endpoint) {
      throw new Error('Endpoint does not support Thrift debug transport');
    }

    const thriftMethod = endpoint.fragment
      ? `${endpoint.fragment}:${method.name}`
      : method.name;

    const hdrs = new Headers();
    hdrs.set('content-type', TTEXT_MIME_TYPE);
    for (const [name, value] of Object.entries(headers)) {
      hdrs.set(name, value);
    }

    const httpResponse = await fetch(endpoint.path, {
      headers: hdrs,
      method: 'POST',
      body: `{"method": "${thriftMethod}", "type": "CALL", "args": ${bodyJson}}`,
    });
    const response = await httpResponse.text();
    return response.length > 0 ? response : 'Request sent to one-way function';
  }
}
