#!/usr/bin/env bash
# Builds the fuzzers and their Jazzer wrappers into OSS-Fuzz's $OUT.
# Invoked by OSS-Fuzz's projects/armeria/build.sh from the project root.

set -eu
set -o pipefail

./gradlew --no-daemon :fuzz:copyToFuzzOut -PfuzzOut="$OUT/lib" -PbuildJdkVersion=25 -PnoLint -PnoWeb

RUNTIME_CLASSPATH=$(cd "$OUT" && ls lib/*.jar | sed 's|^|$this_dir/|' | paste -sd: -)

for fuzzer in $(find fuzz/src/main/java -name '*Fuzzer.java'); do
  fuzzer_class=$(echo "${fuzzer#fuzz/src/main/java/}" | sed 's|\.java$||; s|/|.|g')
  fuzzer_name=$(basename -s .java "$fuzzer")

  # Netty uses sun.misc.Unsafe, which UnsafeSanitizer reports.
  cat > "$OUT/$fuzzer_name" << EOF
#!/bin/bash
# LLVMFuzzerTestOneInput for fuzzer detection.
this_dir=\$(dirname "\$0")
if [[ "\$@" =~ (^| )-runs=[0-9]+(\$| ) ]]; then
  mem_settings='-Xmx1900m:-Xss900k'
else
  mem_settings='-Xmx2048m:-Xss1024k'
fi
LD_LIBRARY_PATH="$JVM_LD_LIBRARY_PATH":\$this_dir \\
  \$this_dir/jazzer_driver \\
  --agent_path=\$this_dir/jazzer_agent_deploy.jar \\
  --cp=$RUNTIME_CLASSPATH \\
  --target_class=$fuzzer_class \\
  --jvm_args="\$mem_settings" \\
  --disabled_hooks=com.code_intelligence.jazzer.sanitizers.UnsafeSanitizer \\
  \$@
EOF
  chmod u+x "$OUT/$fuzzer_name"
done
