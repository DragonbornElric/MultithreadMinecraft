#!/bin/bash
# Stop the bot client started by start_bot.sh.
for p in $(ps -eo pid,args | awk '/KnotClient|GradleWrapperMain.*:fabric:runClient|xvfb-run -n 99/ && !/awk/ {print $1}'); do kill "$p" 2>/dev/null; done
sleep 3
pkill -x Xvfb 2>/dev/null || true
