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

const GRPC_UNFRAMED_MIME_TYPE =
  'application/json; charset=utf-8; protocol=gRPC';

export default class GrpcUnframedTransport extends Transport {
  public supportsMimeType(mimeType: string): boolean {
    return mimeType === GRPC_UNFRAMED_MIME_TYPE;
  }

  protected async doSend(
    method: Method,
    bodyJson: string,
    headers: { [name: string]: string },
  ): Promise<string> {
    const endpoint = method.endpoints.find((ep) =>
      ep.availableMimeTypes.includes(GRPC_UNFRAMED_MIME_TYPE),
    );
    if (!endpoint) {
      throw new Error('Endpoint does not support gRPC debug transport');
    }

    const hdrs = new Headers();
    hdrs.set('content-type', GRPC_UNFRAMED_MIME_TYPE);
    for (const [name, value] of Object.entries(headers)) {
      hdrs.set(name, value);
    }

    const httpResponse = await fetch(endpoint.pathMapping, {
      headers: hdrs,
      method: 'POST',
      body: bodyJson,
    });
    const response = await httpResponse.text();
    return response;
  }
}
