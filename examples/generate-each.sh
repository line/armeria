# todo regenerate pom.xml in each module

filename="1.xml"
dirlist=`find . -name build.gradle | perl -p -e  's#\.\/##'| perl -p  -e 's#/build.gradle##'`

for dir in `echo $dirlist | tr " " "\n"`
do
  gradlefile="$dir/build.gradle"
  grep " implementation "
done
