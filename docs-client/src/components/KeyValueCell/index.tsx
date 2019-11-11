import { Input } from '@material-ui/core';
import TableCell from '@material-ui/core/TableCell';
import React, { createRef } from 'react';

export const KeyValueTableCell: React.FunctionComponent = () => {
  const ref = createRef();

  return (
    <TableCell>
      <Input ref={ref} />
    </TableCell>
  );
};
