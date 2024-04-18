
find . -name "build.gradle" | xargs grep --no-filename  " project(\'\:" |awk '{print $2 }' | sort  | uniq -c | awk '{print $2}' > project.properties
find . -name "build.gradle" | xargs grep --no-filename  ' libs.' |awk '{print $2 }'| sort | uniq -c  | awk '{print $2}' > lib.properties
