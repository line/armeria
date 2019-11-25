import React from 'react';
import { HeaderColumn } from 'react-table';
import CustomTable from '../CustomTable';
import { KeyValueTableCellRenderer } from '../KeyValueTableCellRenderer';
import makeData, { Data } from './makeData';

function KeyValueTable() {
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

  const [data, setData] = React.useState(() => makeData(1));
  const [originalData] = React.useState(data);

  const updateData = (rowIndex: number, columnID: string, value: string) => {
    setData((old) => {
      const arr = old.map((row, index) => {
        if (index === rowIndex) {
          return {
            ...old[rowIndex],
            [columnID]: value,
          };
        }
        return row;
      });

      arr.concat(...makeData(1));

      return arr;
    });
  };

  // Let's add a data resetter/randomizer to help
  // illustrate that flow...
  const resetData = () => setData(originalData);

  return (
    <div>
      <button onClick={resetData}>Reset Data</button>
      <CustomTable
        cellRenderer={KeyValueTableCellRenderer}
        columns={columns}
        data={data}
        updateData={updateData}
      />
    </div>
  );
}

export default KeyValueTable;
