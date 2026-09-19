mkdir -p CraftBukkit
cd CraftBukkit

# Function to install craftbukkit dependency
install_craftbukkit() {
    local version=$1
    local filename="craftbukkit-${version}.jar"
    local artifact_version="${version}-R0.1-SNAPSHOT"
    
    # Special case for 1.8 variants as per reference project
    if [[ "$version" == "1.8" ]]; then
        filename="craftbukkit-1.8-R0.1-SNAPSHOT-latest.jar"
    elif [[ "$version" == "1.8.3" ]]; then
        filename="craftbukkit-1.8.3-R0.1-SNAPSHOT-latest.jar"
    elif [[ "$version" == "1.8.8" ]]; then
        filename="craftbukkit-1.8.8-R0.1-SNAPSHOT-latest.jar"
    fi

    echo "Checking for $filename..."

    if [ -f "../../../$filename" ]; then
        echo "Found $filename in root, using it..."
        cp "../../../$filename" .
    elif [ -f "$filename" ]; then
        echo "Found $filename in CraftBukkit directory..."
    else
        echo "Downloading $filename..."
        wget "https://cdn.getbukkit.org/craftbukkit/$filename"
    fi

    mvn install:install-file -Dfile="$filename" -DgroupId=org.bukkit -DartifactId=craftbukkit -Dversion="$artifact_version" -Dpackaging=jar
}

# Install 1.12 (already existed)
install_craftbukkit "1.12"

# Install 1.8 variants (added for 1.8 support)
install_craftbukkit "1.8"
install_craftbukkit "1.8.3"
install_craftbukkit "1.8.8"
