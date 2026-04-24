#!/bin/bash

echo "Starting 10 concurrent TFTP downloads..."

# Loop 10 times
for i in {1..10}
do
   # The '&' at the end sends the curl command to the background
   # so the loop can immediately start the next one.
   curl tftp://127.0.0.1:6969/concurrent_test.bin -o /tmp/test_download_$i.bin &
done

# 'wait' pauses the script until all background background jobs finish
wait 

echo "All 10 downloads complete!"
