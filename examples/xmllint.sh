 find . -name "pom.xml" -type f | xargs -I'{}' xmllint --output '{}' --format '{}'

