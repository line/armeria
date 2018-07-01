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

import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableHead from '@material-ui/core/TableHead';
import TableRow from '@material-ui/core/TableRow';
import Typography from '@material-ui/core/Typography';
import React from 'react';
import { RouteComponentProps } from 'react-router-dom';

import {
  packageName,
  simpleName,
  Specification,
  SpecificationData,
} from '../../lib/specification';

import testSpecification from '../../specification.json';

const specification = new Specification(testSpecification as SpecificationData);

export default class MethodPage extends React.PureComponent<
  RouteComponentProps<{ serviceName: string; methodName: string }>
> {
  public render() {
    const { serviceName, methodName } = this.props.match.params;
    const service = specification.getServiceByName(serviceName);
    if (!service) {
      return <>Not found.</>;
    }
    const method = service.methods.find((m) => m.name === methodName);
    if (!method) {
      return <>Not found.</>;
    }

    return (
      <>
        <Typography variant="headline" paragraph>
          <code>{`${simpleName(service.name)}.${method.name}()`}</code>
        </Typography>
        <Typography variant="body1" paragraph>
          {method.docString}
        </Typography>
        <Typography variant="title">Parameters</Typography>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell>Required</TableCell>
              <TableCell>Type</TableCell>
              <TableCell>Description</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {method.parameters.map((param) => (
              <TableRow key={param.name}>
                <TableCell>
                  <code>{param.name}</code>
                </TableCell>
                <TableCell>{param.requirement}</TableCell>
                <TableCell>
                  <code>
                    {specification.getTypeSignatureHtml(param.typeSignature)}
                  </code>
                </TableCell>
                <TableCell>{param.docString || ''}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        <Typography variant="body1" paragraph />
        <Typography variant="title">Return Type</Typography>
        <Table>
          <TableBody>
            <TableRow>
              <TableCell>
                <code>
                  {specification.getTypeSignatureHtml(
                    method.returnTypeSignature,
                  )}
                </code>
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
        {method.exceptionTypeSignatures.length > 0 && (
          <>
            <Typography variant="body1" paragraph />
            <Typography variant="title">Exceptions</Typography>
            <Table>
              <TableBody>
                {method.exceptionTypeSignatures.map((exception) => (
                  <TableRow key={exception}>
                    <TableCell>
                      <code>
                        {specification.getTypeSignatureHtml(exception)}
                      </code>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </>
        )}
        <Typography variant="body1" paragraph />
        <Typography variant="title">Endpoints</Typography>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Hostname</TableCell>
              <TableCell>Path</TableCell>
              <TableCell>MIME types</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {method.endpoints.map((endpoint) => (
              <TableRow key={`${endpoint.hostnamePattern}/${endpoint.path}`}>
                <TableCell>{endpoint.hostnamePattern}</TableCell>
                <TableCell>{endpoint.path}</TableCell>
                <TableCell>
                  <ul>
                    {endpoint.availableMimeTypes.map((mimeType) => (
                      <li
                        style={{
                          fontWeight:
                            mimeType === endpoint.defaultMimeType
                              ? 'bold'
                              : 'normal',
                        }}
                      >
                        {mimeType}
                      </li>
                    ))}
                  </ul>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </>
    );
  }
}
