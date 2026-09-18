#!/bin/bash

# MatchCoreArena / BedwarsRel Build Script

# Exit on any error
set -e

# Function to check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

echo "Checking build requirements..."

# Check for Java
if ! command_exists java; then
    echo "Error: Java is not installed."
    exit 1
fi

# Check for Maven
if ! command_exists mvn; then
    echo "Error: Maven (mvn) is not installed."
    exit 1
fi

# Check for Wget (needed by get-dependencies.sh)
if ! command_exists wget; then
    echo "Error: wget is not installed. It is required to download dependencies."
    exit 1
fi

echo "Step 1: Installing version-specific dependencies..."
# The project requires multiple CraftBukkit versions in the local repo to compile version-specific modules
if [ -d "build" ] && [ -f "build/get-dependencies.sh" ]; then
    echo "Running build/get-dependencies.sh..."
    cd build
    chmod +x get-dependencies.sh
    ./get-dependencies.sh
    cd ..
else
    echo "Warning: build/get-dependencies.sh not found or directory missing."
fi

echo "Step 2: Building the project with Maven..."
# Perform a clean install, skipping tests for speed
mvn clean install -DskipTests -fae

echo "-------------------------------------------------------"
echo "Build Process Completed!"
echo "The compiled plugin can be found at: plugin/target/BedwarsRel-*.jar"
echo "-------------------------------------------------------"
