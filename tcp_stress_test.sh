#!/bin/bash

echo "Starting 10 concurrent TCP transfers..."

# Loop 10 times
for i in {1..10}
do
   java -jar TFTP-TCP-Client/target/TFTP-TCP-Client-1.0-SNAPSHOT.jar get concurrent_test.bin localhost 6970 &
done

wait 

echo "All 10 TCP downloads complete!"
