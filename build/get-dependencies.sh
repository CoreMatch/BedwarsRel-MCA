mkdir -p CraftBukkit
cd CraftBukkit

# Function to install craftbukkit dependency from local root
install_craftbukkit() {
    local version=$1
    local artifact_version="${version}-R0.1-SNAPSHOT"
    local filename=""

    # Match filenames currently in root
    if [[ "$version" == "1.12" ]]; then
        filename="craftbukkit-1.12.jar"
    elif [[ "$version" == "1.8" ]]; then
        filename="craftbukkit-1.8-R0.1-SNAPSHOT.jar"
    elif [[ "$version" == "1.8.3" ]]; then
        filename="craftbukkit-1.8.3.jar"
    elif [[ "$version" == "1.8.8" ]]; then
        filename="craftbukkit-1.8.8.jar"
    fi

    echo "Checking for $filename in project root..."

    if [ -f "../../$filename" ]; then
        echo "Found $filename, copying and installing..."
        cp "../../$filename" .
        mvn install:install-file -Dfile="$filename" -DgroupId=org.bukkit -DartifactId=craftbukkit -Dversion="$artifact_version" -Dpackaging=jar
    else
        echo "Error: $filename not found in project root!"
        echo "Please place the required Bukkit JAR files in the project root directory."
        # We don't exit here to allow other versions to be installed if they exist, 
        # but the build will likely fail later if this dependency is missing.
    fi
}

# Install required versions locally
install_craftbukkit "1.12"
install_craftbukkit "1.8"
install_craftbukkit "1.8.3"
install_craftbukkit "1.8.8"
