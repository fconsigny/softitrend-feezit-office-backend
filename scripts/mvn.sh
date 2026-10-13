#!/bin/sh
set -eu
JAVA_HOME="${FEEZIT_JAVA_HOME:-/home/stellabot/.local/jdk-25.0.2}"
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH
exec "${OFFICE_MAVEN_HOME:-/home/stellabot/.local/apache-maven-3.9.16}/bin/mvn" "$@"
