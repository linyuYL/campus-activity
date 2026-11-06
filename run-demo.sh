#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
exec java -Duser.timezone=UTC -jar release/campus-activity.jar
