mkdir -p CraftBukkit
cd CraftBukkit
wget https://cdn.getbukkit.org/craftbukkit/craftbukkit-1.12.jar
mvn install:install-file -Dfile=craftbukkit-1.12.jar -DgroupId=org.bukkit -DartifactId=craftbukkit -Dversion=1.12-R0.1-SNAPSHOT -Dpackaging=jar
