import { Button } from '@material-ui/core';
import TextField from '@material-ui/core/TextField';
import parseLoosely from 'jsonic';
import React, { useState } from 'react';
import jsonPrettify from '../../lib/json-prettify';
import KeyValueTable from '../KeyValueTable';
import { CreateDefaultRow, Row, ValueListContext } from './valueListContext';

interface KeyValueEditorProps {
  defaultValue?: string;
  keyName?: string;
  valueName?: string;
  onChange:any;
}
enum DisplayType {
  KeyValue,
  Plain,
}

export const StrToRowList = (str: string): Row[] => {
  let rowList: Row[] = [];
  try {
    const obj = parseLoosely(str);
    rowList = Object.keys(obj).map((v) => {
      return {
        key: v,
        value: obj[v],
      };
    });
  } catch (e) {
    // prevent to stop running js error caused
  }
  return rowList;
};

export const RowListToStr = (rowList: Row[]): string => {
  return JSON.stringify(
    rowList.reduce(
      (acc: { [key: string]: string }, cur: { key: string; value: string }) => {
        if (cur.key || cur.value) {
          acc[cur.key || ''] = cur.value || '';
        }
        return acc;
      },
      {},
    ),
  );
};

const KeyValueEditor: React.FunctionComponent<KeyValueEditorProps> = ({
  rowListString,
  keyName = 'Key',
  valueName = 'Value',
  setRowListString,
}) => {
  const [displayType, setDisplayType] = useState(DisplayType.KeyValue);
  // const [rowList, setRowList] = useState<Row[]>(defaultValue ? StrToRowList(defaultValue) || [CreateDefaultRow()]);
  const [bulkString, setBulkString] = useState(JSON.stringify(rowListString));

  const DisplayTypeButton: React.FunctionComponent = () => {
    if (displayType === DisplayType.Plain) {
      return (
        <Button
          onClick={() => {
            const newRowList = StrToRowList(bulkString);
            newRowList.push(CreateDefaultRow());

            setRowListString(RowListToStr(newRowList));
            setDisplayType(DisplayType.KeyValue);
          }}
        >
          Key Value Type
        </Button>
      );
    }

    return (
      <Button
        onClick={() => {
          setBulkString(jsonPrettify(rowListString));
          setDisplayType(DisplayType.Plain);
        }}
      >
        Bulk Edit
      </Button>
    );
  };
  return (
    <ValueListContext.Provider value={[rowListString, setRowListString]}>
      <DisplayTypeButton />
      {displayType === DisplayType.KeyValue ? (
        <KeyValueTable
          defaultKeyValueListString={rowListString}
          keyName={keyName}
          valueName={valueName}
        />
      ) : (
        <TextField
          multiline
          fullWidth
          rows={8}
          inputProps={{
            className: 'code',
          }}
          onChange={(e) => {
            setBulkString(e.target.value);
          }}
          value={bulkString}
        />
      )}
    </ValueListContext.Provider>
  );
};
export default React.memo(KeyValueEditor);
