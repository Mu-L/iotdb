#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

current_path=$(cd $(dirname $0);pwd)
start_what=$1

function on_stop(){
    echo "#### on_stop $start_what ####"
    if [[ "$start_what" != "confignode" ]]; then
        echo "###### manually flush ######";
        start-cli.sh -e "flush;" || true
    fi
    if [[ "$start_what" == "ainode" ]]; then
        stop-ainode.sh
    else
        stop-standalone.sh
    fi
}

trap 'on_stop' SIGTERM SIGKILL SIGQUIT


replace-conf-from-env.sh $start_what

case "$1" in
    datanode)
        exec start-datanode.sh
        ;;
    confignode)
        exec start-confignode.sh
        ;;
    ainode)
        exec start-ainode.sh
        ;;
    all)
        start-confignode.sh > /dev/null 2>&1 &
        sleep 5
        exec start-datanode.sh
        ;;
    *)
        echo "bad parameter!"
        exit -1
        ;;
esac
