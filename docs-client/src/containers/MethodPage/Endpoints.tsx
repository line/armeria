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

import List from '@material-ui/core/List';
import ListItem from '@material-ui/core/ListItem';
import ListItemText from '@material-ui/core/ListItemText';
import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableHead from '@material-ui/core/TableHead';
import TableRow from '@material-ui/core/TableRow';
import Typography from '@material-ui/core/Typography';
import TableContainer from '@material-ui/core/TableContainer';
import * as React from 'react';
import { Endpoint, Method } from '../../lib/specification';

import Section from '../../components/Section';

interface Props {
  method: Method;
}

const Endpoints: React.FunctionComponent<Props> = (props) => (
  <Section>
    <Typography variant="h6">Endpoints</Typography>
    <TableContainer>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Hostname</TableCell>
            <TableCell>Path</TableCell>
            <TableCell>MIME types</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {props.method.endpoints.map((endpoint) => (
            <TableRow
              key={`${endpoint.hostnamePattern}/${endpoint.pathMapping}`}
            >
              <TableCell>{endpoint.hostnamePattern}</TableCell>
              <TableCell>{endpointPathString(endpoint)}</TableCell>
              <TableCell>
                <List dense>
                  {endpoint.availableMimeTypes.map((mimeType) => (
                    <ListItem key={mimeType}>
                      <ListItemText
                        primary={mimeType}
                        primaryTypographyProps={{
                          style: {
                            fontWeight:
                              mimeType === endpoint.defaultMimeType
                                ? 'bold'
                                : 'normal',
                          },
                        }}
                      />
                    </ListItem>
                  ))}
                </List>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  </Section>
);

function endpointPathString(endpoint: Endpoint): string {
  if (endpoint.regexPathPrefix) {
    return `${endpoint.regexPathPrefix} ${endpoint.pathMapping}`;
  }
  return endpoint.pathMapping;
}

export default React.memo(Endpoints);
