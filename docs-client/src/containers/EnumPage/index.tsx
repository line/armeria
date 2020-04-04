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
} from '../../lib/specification';

import Section from '../../components/Section';

interface OwnProps {
  specification: Specification;
}

type Props = OwnProps & RouteComponentProps<{ name: string }>;

const EnumPage: React.FunctionComponent<Props> = ({ match, specification }) => {
  const data = specification.getEnumByName(match.params.name);
  if (!data) {
    return <>Not found.</>;
  }

  const hasIntValue = data.values.some(value => !!value.intValue);
  const hasDocString = data.values.some(value => !!value.docString);

  return (
    <>
      <Typography variant="h5">
        <code>{simpleName(data.name)}</code>
      </Typography>
      <Typography variant="subtitle1" paragraph>
        <code>{packageName(data.name)}</code>
      </Typography>
      <Typography variant="body2" paragraph>
        {data.docString}
      </Typography>
      <Section>
        <Typography variant="h6">Values</Typography>
        <Table>
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              {hasIntValue && <TableCell>Int Value</TableCell>}
              {hasDocString && <TableCell>Description</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {data.values.length > 0 ? (
              data.values.map(value => (
                <TableRow key={value.name}>
                  <TableCell>
                    <code>{value.name}</code>
                  </TableCell>
                  {hasIntValue && <TableCell>{value.intValue}</TableCell>}
                  {hasDocString && <TableCell>{value.docString}</TableCell>}
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={2}>There are no values.</TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </Section>
    </>
  );
};

export default React.memo(EnumPage);
