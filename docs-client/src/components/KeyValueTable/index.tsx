import React, { Dispatch, SetStateAction } from 'react';
import { HeaderColumn } from 'react-table';
import CustomTable from '../CustomTable';
import { KeyValueTableCellRenderer } from '../KeyValueTableCellRenderer';
import makeData, { Data } from './makeData';

interface Props {
  data: Data[];
  setData: Dispatch<SetStateAction<Data[]>>;
  resetData: () => void;
}

const KeyValueTable: React.FunctionComponent<Props> = ({
  data,
  setData,
  resetData,
}) => {
  const columns: HeaderColumn<Data, keyof Data>[] = React.useMemo(
    () => [
      {
        accessor: 'fieldName',
        Header: 'fieldName',
        cell: KeyValueTableCellRenderer,
      },
      {
        accessor: 'fieldValue',
        Header: 'fieldValue',
        cell: KeyValueTableCellRenderer,
      },
    ],
    [],
  );

  const updateCell = (
    rowIndex: number,
    columnID: string,
    value: string,
    isLastRow: boolean,
  ) => {
    setData((old) => {
      let tmp = old.map((row, index) => {
        if (index === rowIndex) {
          return {
            ...old[rowIndex],
            [columnID]: value,
          };
        }
        return row;
      });
      if (isLastRow) tmp = tmp.concat([...makeData(1)]);
      return tmp;
    });
  };

  const removeRow = (rowIndex: number) => {
    setData((old) => old.filter((_, i) => rowIndex !== i));
  };

  return (
    <div>
      <button onClick={resetData}>Reset Data</button>
      <CustomTable
        cellRenderer={KeyValueTableCellRenderer}
        columns={columns}
        data={data}
        updateCell={updateCell}
        removeRow={removeRow}
      />
    </div>
  );
};

export default KeyValueTable;
