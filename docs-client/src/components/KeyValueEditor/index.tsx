import { Button } from '@material-ui/core';
import React, { useState } from 'react';
import KeyValueTable from '../KeyValueTable';
import { Row, ValueListContext } from './valueListContext';

interface KeyValueEditorProps {
  defaultValue?: Row[];
}
enum DisplayType {
  KeyValue,
  Plain,
}

const KeyValueEditor: React.FunctionComponent<KeyValueEditorProps> = ({
  defaultValue,
}) => {
  const [displayType, setDisplayType] = useState(DisplayType.Plain);
  const [rowList, setRowList] = useState<Row[]>(
    defaultValue || [
      {
        index: 0,
        key: '',
        value: '',
      },
    ],
  );

  const DisplayTypeButton: React.FunctionComponent = () => {
    switch (displayType) {
      case DisplayType.Plain:
        return (
          <Button onClick={() => setDisplayType(DisplayType.KeyValue)}>
            Key Value Type
          </Button>
        );
    }

    return (
      <Button onClick={() => setDisplayType(DisplayType.Plain)}>
        Bulk Edit
      </Button>
    );
  };
  return (
    <ValueListContext.Provider value={[rowList, setRowList]}>
      <DisplayTypeButton />
      <KeyValueTable />
    </ValueListContext.Provider>
  );
};
export default React.memo(KeyValueEditor);
