#!/bin/sh

# Creates a list of dependencies of this project in the form
# service-<project>/<sub-project>:<dependency-with-version>

# set -x

# Change this accordingly for your project
PROJECT=policies
SUB_PROJECT=engine

#----------

TMP_FILE=$(mktemp manifest.XXXXX)


mvn dependency:list | grep -e ':compile$' -e ':runtime$' | sed -e 's/\[INFO\] *//' -e 's/:compile$//' > $TMP_FILE
cat $TMP_FILE | sed -e "s/^/service-${PROJECT}\/${SUB_PROJECT}:/"  > manifest.txt

rm $TMP_FILE