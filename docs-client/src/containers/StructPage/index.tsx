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

export default class StructPage extends React.PureComponent<
  RouteComponentProps<{ name: string }>
> {
  public render() {
    const data = specification.getStructByName(this.props.match.params.name);
    if (!data) {
      return <>Not found.</>;
    }

    return (
      <>
        <Typography variant="headline">{simpleName(data.name)}</Typography>
        <Typography variant="subheading" paragraph>
          {packageName(data.name)}
        </Typography>
        <Typography variant="body1" paragraph>
          {data.docString}
        </Typography>
        {data.fields.length > 0 && (
          <>
            <Typography variant="title">Fields</Typography>
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
                {data.fields.map((field) => (
                  <TableRow key={field.name}>
                    <TableCell>
                      <code>{field.name}</code>
                    </TableCell>
                    <TableCell>{field.requirement}</TableCell>
                    <TableCell>
                      <code>
                        {specification.getTypeSignatureHtml(
                          field.typeSignature,
                        )}
                      </code>
                    </TableCell>
                    <TableCell>{field.docString}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </>
        )}
      </>
    );
  }
}
