import { Button } from '@material-ui/core';
import TextField from '@material-ui/core/TextField';
import parseLoosely from 'jsonic';
import React, { useState } from 'react';
import jsonPrettify from '../../lib/json-prettify';
import KeyValueTable from '../KeyValueTable';
import makeData, { Data } from '../KeyValueTable/makeData';

enum DisplayType {
  KeyValue,
  Plain,
}

const KeyValueEditor: React.FunctionComponent = () => {
  const [displayType, setDisplayType] = useState<DisplayType>(
    DisplayType.KeyValue,
  );

  const [data, setData] = React.useState<Data[]>(() => makeData(1));
  const [originalData] = React.useState(data);
  const resetData = () => setData(originalData);
  const [string, setString] = React.useState<string>('');

  const DisplayTypeButton: React.FunctionComponent = () => {
    if (displayType === DisplayType.Plain) {
      return (
        <Button
          onClick={() => {
            const tmp = parseLoosely(string);
            let arr = Object.keys(tmp).map((v) => {
              return {
                fieldName: v,
                fieldValue: tmp[v],
              };
            });
            arr = arr.concat([...makeData(1)]);
            setDisplayType(DisplayType.KeyValue);
            setData(arr);
          }}
        >
          Key Value Type
        </Button>
      );
    }

    return (
      <Button
        onClick={() => {
          setDisplayType(DisplayType.Plain);
          setString(
            jsonPrettify(
              JSON.stringify(
                data.reduce((acc: any, cur: Data) => {
                  if (cur.fieldName || cur.fieldValue) {
                    acc[cur.fieldName || ''] = cur.fieldValue || '';
                  }
                  return acc;
                }, {}),
              ),
            ),
          );
        }}
      >
        Bulk Edit
      </Button>
    );
  };

  return (
    <div>
      <DisplayTypeButton />
      {displayType === DisplayType.KeyValue ? (
        <KeyValueTable data={data} setData={setData} resetData={resetData} />
      ) : (
        <TextField
          onChange={(e) => setString(e.target.value)}
          value={string}
          multiline
          fullWidth
          rows={8}
          inputProps={{
            className: 'code',
          }}
        />
      )}
    </div>
  );
};

export default React.memo(KeyValueEditor);
