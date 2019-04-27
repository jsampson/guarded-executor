#!/bin/bash

set -e -u -x

. run-remote-config.sh

mvn package

TEMP_DIR=`mktemp -d run-remote-tmp-XXXXXX`
JAR="guarded-executor-0.1-SNAPSHOT-test-with-deps.jar"

ssh $SSH_OPTIONS $REMOTE_USER "mkdir $TEMP_DIR"

scp $SSH_OPTIONS target/$JAR $REMOTE_USER:$TEMP_DIR/

ssh $SSH_OPTIONS $REMOTE_USER "$JAVA_BIN_DIR/java \
  -Xcomp -Xbatch -XX:-UseBiasedLocking \
  -Dreport.file.name=$TEMP_DIR/report.html \
  -Dreport.description=\"$DESCRIPTION\" \
  -jar $TEMP_DIR/$JAR"

scp $SSH_OPTIONS $REMOTE_USER:$TEMP_DIR/report.html $TEMP_DIR/report.html

if [ "$SHUTDOWN_AFTER_RUN" == "yes" ]
then
  ssh $SSH_OPTIONS $REMOTE_USER "sudo shutdown -h now"
fi
