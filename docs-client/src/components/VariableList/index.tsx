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

import { Specification } from '../../lib/specification';

interface Variable {
  name: string;
  requirement: string;
  typeSignature: string;
  docString?: string | JSX.Element;
}

interface Props {
  title: string;
  variables: Variable[];
  specification: Specification;
}

export default function({ title, variables, specification }: Props) {
  return (
    <>
      <Typography variant="title">{title}</Typography>
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
          {variables.length > 0 ? (
            variables.map((variable) => (
              <TableRow key={variable.name}>
                <TableCell>
                  <code>{variable.name}</code>
                </TableCell>
                <TableCell>{variable.requirement}</TableCell>
                <TableCell>
                  <code>
                    {specification.getTypeSignatureHtml(variable.typeSignature)}
                  </code>
                </TableCell>
                <TableCell>{variable.docString}</TableCell>
              </TableRow>
            ))
          ) : (
            <TableRow>
              <TableCell colSpan={4}>
                There are no {title.toLowerCase()}
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
      <Typography variant="body1" paragraph />
    </>
  );
}
