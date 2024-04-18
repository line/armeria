# todo regenerate pom.xml in each module
function gen() {
  gradlefile=$1
  gradledir=`dirname $gradlefile`
  gradlename=`basename $gradlefile`
  pushd gradlefile
  while read line; do
    if [[ $line =~ bird ]] ; 
      then echo $line; 
    elif [ $line =~ iml ]; then
        
    fi
  done <gradlename
  popd
}

#dirlist=`find . -name build.gradle | perl -p -e  's#\.\/##'| perl -p  -e 's#/build.gradle##'`
dirlist=`find . -name build.gradle`

for gradlefile in `find . -name build.gradle`
do
  gen $gradlefile
done


