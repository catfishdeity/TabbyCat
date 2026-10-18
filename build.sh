#!/bin/bash
set -e
javac --release 8 -Xlint:-options -d bin -sourcepath src src/tabsequencer/TabbyCat.java
cp icon.png bin/
jar --create --file TabbyCat.jar --main-class tabsequencer.TabbyCat -C bin .
echo "Built TabbyCat.jar"
