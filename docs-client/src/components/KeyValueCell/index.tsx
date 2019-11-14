import TableCell from '@material-ui/core/TableCell';
import React, { useState } from 'react';
import { ACTION } from '../KeyValueEditor/valueListContext';

interface Props {
  initialValue?: string;
  columnIdx: number;
  rowIdx: number;
  cellIdx: number;
}
export const KeyValueTableCell: React.FC<Props> = ({
  initialValue,
  columnIdx,
  rowIdx,
  cellIdx,
  onUpdate,
}) => {
  const [value, setValue] = useState(initialValue);

  const onCellChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setValue(e.target.value);
  };

  const onCellBlur = (e: React.FocusEvent<HTMLInputElement>) => {
    onUpdate(ACTION.CHANGE_CELL, { columnIdx, rowIdx, value: e.target.value });
  };

  return (
    <TableCell key={cellIdx}>
      <input value={value} onChange={onCellChange} onBlur={onCellBlur} />
    </TableCell>
  );
};
