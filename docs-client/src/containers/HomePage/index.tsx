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
import Tooltip from '@material-ui/core/Tooltip';
import Typography from '@material-ui/core/Typography';
import TableContainer from '@material-ui/core/TableContainer';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import React from 'react';
import Section from '../../components/Section';
import { convertLongToUTCDate, Versions } from '../../lib/versions';

interface OwnProps {
  versions?: Versions;
}

dayjs.extend(relativeTime);

const formatReadableTime = (dateTime: number): string => {
  if (!dateTime) {
    return 'Unknown';
  }

  return dayjs().to(dayjs(dateTime));
};

type Props = OwnProps;

const HomePage: React.FunctionComponent<Props> = ({ versions }) => {
  const tableRows: JSX.Element[] = [];

  if (versions) {
    versions.getVersions().forEach((version) => {
      tableRows.push(
        <TableRow key={version.artifactId}>
          <TableCell>{version.artifactId}</TableCell>
          <TableCell>
            {version.artifactVersion}
            {versions.getArmeriaArtifactVersion() !== version.artifactVersion
              ? '(!)'
              : ''}
          </TableCell>
          <TableCell>
            <Tooltip title={convertLongToUTCDate(version.commitTimeMillis)}>
              <Typography variant="body2">
                {formatReadableTime(version.commitTimeMillis)}
              </Typography>
            </Tooltip>
          </TableCell>
          <TableCell>
            <Typography variant="body2">
              <a
                href={`https://github.com/line/armeria/commit/${version.longCommitHash}`}
              >
                {version.shortCommitHash}
              </a>
            </Typography>
          </TableCell>
          <TableCell>{version.repositoryStatus}</TableCell>
        </TableRow>,
      );
    });
  }

  return (
    <>
      <Typography variant="h6" paragraph>
        Welcome to the Armeria documentation service
      </Typography>
      <Typography variant="body2">
        This page provides the information about the RPC services and its
        related data types in this server.
      </Typography>
      <Typography variant="body2" paragraph>
        Please start to navigate via the sidebar on the left or the menu button
        on the top right.
      </Typography>
      {tableRows.length > 0 && (
        <>
          <Typography variant="subtitle1" paragraph>
            Version information
          </Typography>
          <Section>
            <TableContainer>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell>Module</TableCell>
                    <TableCell>Version</TableCell>
                    <TableCell>Commit Time</TableCell>
                    <TableCell>Commit ID</TableCell>
                    <TableCell>Repository Status</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>{tableRows}</TableBody>
              </Table>
            </TableContainer>
          </Section>
        </>
      )}
    </>
  );
};

export default React.memo(HomePage);
