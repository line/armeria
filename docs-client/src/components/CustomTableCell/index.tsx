import TableCell from '@material-ui/core/TableCell';
import React from 'react';

interface Props {
  cell: any;
  row: any;
  column: any;
  updateMyData: any;
}

export const CustomTableCell: React.FunctionComponent<Props> = ({
  cell: { value: initialValue },
  row: { index },
  column: { id },
  updateMyData, // This is a custom function that we supplied to our table instance
}) => {
  // We need to keep and update the state of the cell normally
  const [value, setValue] = React.useState(initialValue);

  const onChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setValue(e.target.value);
  };

  // We'll only update the external data when the input is blurred
  const onBlur = () => {
    updateMyData(index, id, value);
  };

  // If the initialValue is changed externall, sync it up with our state
  React.useEffect(() => {
    setValue(initialValue);
  }, [initialValue]);

  // we'll use native input because input of material-ui doesn't have onBlur property
  return (
    <TableCell>
      <input value={value} onChange={onChange} onBlur={onBlur} />
    </TableCell>
  );
};
