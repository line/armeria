/*
 * Copyright 2019 LINE Corporation
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
import TableRow from '@material-ui/core/TableRow';
import Typography from '@material-ui/core/Typography';
import TableContainer from '@material-ui/core/TableContainer';
import * as React from 'react';
import { Method, Specification } from '../../lib/specification';

import Section from '../../components/Section';

interface Props {
  method: Method;
  specification: Specification;
}

const Exceptions: React.FunctionComponent<Props> = (props) => (
  <Section>
    <Typography variant="h6">Exceptions</Typography>
    <TableContainer>
      <Table>
        <TableBody>
          {props.method.exceptionTypeSignatures.length > 0 ? (
            props.method.exceptionTypeSignatures.map((exception) => (
              <TableRow key={exception}>
                <TableCell>
                  <code>
                    {props.specification.getTypeSignatureHtml(exception)}
                  </code>
                </TableCell>
              </TableRow>
            ))
          ) : (
            <TableRow key="empty exception">
              <TableCell>There are no exceptions</TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </TableContainer>
  </Section>
);

export default React.memo(Exceptions);
