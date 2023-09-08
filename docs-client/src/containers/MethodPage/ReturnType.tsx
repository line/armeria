/*
 * Copyright 2022 LINE Corporation
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

import ExpandLess from '@material-ui/icons/ExpandLess';
import ExpandMore from '@material-ui/icons/ExpandMore';
import { useReducer } from 'react';
import { makeStyles } from '@material-ui/core';
import VariableList from '../../components/VariableList';
import Section from '../../components/Section';
import { Method, Specification } from '../../lib/specification';

const useStyles = makeStyles({
  expand: {
    textAlign: 'end',
    '& svg': {
      verticalAlign: 'middle',
    },
  },
});

interface Props {
  method: Method;
  specification: Specification;
}

const ReturnType: React.FunctionComponent<Props> = ({
  method,
  specification,
}) => {
  const returnTypeVariables =
    specification.getStructByName(method.returnTypeSignature)?.fields || [];

  const [returnTypeExpanded, toggleReturnTypeExpanded] = useReducer(
    (value) => !value,
    false,
  );

  const styles = useStyles();
  const hasVariables = returnTypeVariables.length > 0;

  return (
    <Section>
      <Typography variant="h6">Return Type</Typography>
      <TableContainer>
        <Table>
          <TableBody>
            <TableRow onClick={toggleReturnTypeExpanded}>
              <TableCell>
                <code>
                  {specification.getTypeSignatureHtml(
                    method.returnTypeSignature,
                  )}
                </code>
              </TableCell>
              {hasVariables && (
                <TableCell className={styles.expand}>
                  {returnTypeExpanded ? <ExpandLess /> : <ExpandMore />}
                </TableCell>
              )}
            </TableRow>
          </TableBody>
        </Table>
      </TableContainer>
      {returnTypeExpanded && hasVariables && (
        <VariableList
          key={method.returnTypeSignature}
          title=""
          variables={returnTypeVariables}
          specification={specification}
        />
      )}
    </Section>
  );
};

export default React.memo(ReturnType);
