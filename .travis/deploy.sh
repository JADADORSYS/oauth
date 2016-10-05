#!/usr/bin/env bash
set -e

echo "TRAVIS_TAG: $TRAVIS_TAG"

TAG_PATTERN="^oauth-parent-([[:digit:]]+\.)+[[:digit:]]+$"

if [[ ${TRAVIS_TAG} =~ ${TAG_PATTERN} ]]; then
  echo "RELEASE TAG -> publish $TRAVIS_TAG to mvn central";
  #mvn deploy javadoc:javadoc gpg:sign -Prelease -DskipTests -B -U -Pwildfly;
else
  echo "NO RELEASE TAG -> don't publish to mvn central";
  #mvn package -U -Pwildfly;
fi


