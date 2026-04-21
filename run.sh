#!/bin/bash

# Default port if not provided
PORT=$1
if [ -z "$PORT" ]; then
  PORT=8080
fi

echo "Starting Proxy Server on port $PORT..."

# Run using Gradle Wrapper
./gradlew run --args="$PORT"