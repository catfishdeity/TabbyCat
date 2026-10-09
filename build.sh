#!/bin/bash
set -e
javac --release 16 -d bin -sourcepath src src/tabsequencer/TabbyCat.java
jar --create --file TabbyCat.jar --main-class tabsequencer.TabbyCat -C bin .
echo "Built TabbyCat.jar"
