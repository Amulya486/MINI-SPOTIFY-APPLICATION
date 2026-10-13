#!/bin/sh
# usage: ./run.sh build | web | terminal | backend
case "$1" in
  build)    mkdir -p server/out && javac -d server/out server/src/simplestream/*.java client/src/simplestream/*.java && echo "Build OK" ;;
  web)      # starts the 3 backend nodes in the background, then the website
            java -cp server/out simplestream.MusicServer &
            BACK=$!
            trap 'kill $BACK 2>/dev/null' EXIT INT TERM    # Ctrl+C stops the backend too
            sleep 3
            java -cp server/out simplestream.SpotifyWeb ;;
  terminal) java -cp server/out simplestream.SpotifyTerminal ;;
  backend)  java -cp server/out simplestream.MusicServer ;;     # backend only (optional)
  *) echo "usage: ./run.sh build|web|terminal|backend" ;;
esac