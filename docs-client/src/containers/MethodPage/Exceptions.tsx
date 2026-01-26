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
import { makeStyles } from '@material-ui/core';
import * as React from 'react';
import {
  DescribedTypeSignature,
  Method,
  Specification,
} from '../../lib/specification';

import Section from '../../components/Section';
import Description from '../../components/Description';

const useStyles = makeStyles({
  description: {
    margin: 0,
  },
});

interface Props {
  method: Method;
  specification: Specification;
}

interface ExceptionRowProps {
  exception: DescribedTypeSignature;
  specification: Specification;
}

const ExceptionRow: React.FunctionComponent<ExceptionRowProps> = ({
  exception,
  specification,
}) => {
  const styles = useStyles();
  const hasDescription =
    exception.descriptionInfo && exception.descriptionInfo.docString;

  return (
    <TableRow>
      <TableCell>
        <code>
          {specification.getTypeSignatureHtml(exception.typeSignature)}
        </code>
      </TableCell>
      <TableCell>
        {hasDescription && (
          <pre className={styles.description}>
            <Description descriptionInfo={exception.descriptionInfo} />
          </pre>
        )}
      </TableCell>
    </TableRow>
  );
};

const Exceptions: React.FunctionComponent<Props> = (props) => (
  <Section>
    <Typography variant="h6">Exceptions</Typography>
    <TableContainer>
      <Table>
        <TableBody>
          {props.method.exceptions.length > 0 ? (
            props.method.exceptions.map((exception) => (
              <ExceptionRow
                key={exception.typeSignature}
                exception={exception}
                specification={props.specification}
              />
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
