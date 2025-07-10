#!/bin/bash

# Set the paths to the directories
DIR1="/Users/user/Projects/upstream-armeria/thrift/thrift0.12/src/test/thrift"
DIR2="/Users/user/Projects/upstream-armeria/thrift/thrift0.13/src/test/thrift"

# Loop through each file in DIR1
for file in "$DIR1"/*; do
    filename=$(basename "$file")
    file2="$DIR2/$filename"

    if [ -f "$file2" ]; then
        echo "=== Diff for $filename ==="
        diff "$file" "$file2"
        echo
    else
        echo "File $filename does not exist in $DIR2"
    fi
done
