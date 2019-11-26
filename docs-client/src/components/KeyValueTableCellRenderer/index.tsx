import React from 'react';

interface Props {
  cell: {
    value: string;
  };
  row: {
    index: number;
  };
  column: {
    id: number;
  };
  updateData: (...args: any) => void;
  isLastRow: boolean;
}

export const KeyValueTableCellRenderer: React.FC<Props> = ({
  cell: { value: initialValue },
  row: { index },
  column: { id },
  updateData,
  isLastRow,
}) => {
  // We need to keep and update the state of the cell normally
  const [value, setValue] = React.useState();

  const onChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setValue(e.target.value);
  };
  // We'll only update the external data when the input is blurred
  const onBlur = () => {
    updateData(index, id, value, isLastRow);
  };

  // If the initialValue is changed externall, sync it up with our state
  React.useEffect(() => {
    setValue(initialValue);
  }, [initialValue]);

  // we'll use native input because input of material-ui doesn't have onBlur property
  return <input value={value || ''} onChange={onChange} onBlur={onBlur} />;
};
