import React from 'react';
import { Cell } from 'react-table';
import { Data } from '../KeyValueTable/makeData';

export const KeyValueTableCellRenderer: Cell<Data> = ({
  cell: { value: initialValue },
  row: { index },
  column: { id },
  updateMyData,
}) => {
  // We need to keep and update the state of the cell normally
  const [value, setValue] = React.useState();

  const onChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setValue(e.target.value);
  };

  // We'll only update the external data when the input is blurred
  const onBlur = () => {
    updateData(index, id, value);
  };

  // If the initialValue is changed externall, sync it up with our state
  React.useEffect(() => {
    setValue(initialValue);
  }, [initialValue]);

  // we'll use native input because input of material-ui doesn't have onBlur property
  return <input value={value} onChange={onChange} onBlur={onBlur} />;
};
