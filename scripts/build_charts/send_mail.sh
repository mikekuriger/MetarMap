#!/bin/bash

# Mike Kuriger michael.kuriger@thryv.com
# March 21, 2024
# Notify user that their VM build is complete

# send mail to owners
mailto=mk7193@thryv.com
task=$1
subject="Task $task Complete"

cat /data/build_charts/logs/$1 > /tmp/mail

cat /tmp/mail | mail -s "$subject" ${mailto} || exit 1 
