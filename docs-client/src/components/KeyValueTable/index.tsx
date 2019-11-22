import React from 'react';
import { HeaderColumn } from 'react-table';
import CustomTable from '../CustomTable';
import { KeyValueTableCell } from '../KeyValueTableCellRenderer';
import makeData, { Data } from './makeData';

function KeyValueTable() {
  const columns: HeaderColumn<Data, keyof Data>[] = React.useMemo(
    () => [
      {
        id: 0,
        accessor: 'header',
        Header: 'Header',
        cell: KeyValueTableCell,
      },
      {
        id: 1,
        accessor: 'value',
        Header: 'Value',
        cell: KeyValueTableCell,
      },
    ],
    [],
  );

  const [data, setData] = React.useState(() => makeData(1));
  const [originalData] = React.useState(data);

  // We need to keep the table from resetting the pageIndex when we
  // Update data. So we can keep track of that flag with a ref.

  // When our cell renderer calls updateMyData, we'll use
  // the rowIndex, columnID and new value to update the
  // original data
  const updateData = (rowIndex: number, columnID: string, value: string) => {
    // We also turn on the flag to not reset the page
    setData((old) =>
      old.map((row, index) => {
        if (index === rowIndex) {
          return {
            ...old[rowIndex],
            [columnID]: value,
          };
        }
        return row;
      }),
    );
    // tslint:disable-next-line:no-console
    console.log(data);
  };

  // Let's add a data resetter/randomizer to help
  // illustrate that flow...
  const resetData = () => setData(originalData);

  return (
    <div>
      <button onClick={resetData}>Reset Data</button>
      <CustomTable columns={columns} data={data} updateData={updateData} />
    </div>
  );
}

export default KeyValueTable;
