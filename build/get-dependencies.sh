mkdir -p CraftBukkit
cd CraftBukkit

# Check if file exists in root or here
if [ -f "../../craftbukkit-1.12.jar" ]; then
    echo "Found craftbukkit-1.12.jar in root, using it..."
    cp "../../craftbukkit-1.12.jar" .
elif [ -f "craftbukkit-1.12.jar" ]; then
    echo "Found craftbukkit-1.12.jar in CraftBukkit directory..."
else
    echo "Downloading craftbukkit-1.12.jar..."
    wget https://cdn.getbukkit.org/craftbukkit/craftbukkit-1.12.jar
fi

mvn install:install-file -Dfile=craftbukkit-1.12.jar -DgroupId=org.bukkit -DartifactId=craftbukkit -Dversion=1.12-R0.1-SNAPSHOT -Dpackaging=jar -DpomFile=../craftbukkit-minimal.pom
