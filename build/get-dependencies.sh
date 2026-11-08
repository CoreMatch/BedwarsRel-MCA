#!/usr/bin/env bash
#
# Installs the four out-of-band CraftBukkit jars this build needs into the
# local Maven repository.
#
# Where the jars come from (in order):
#   1. If a jar already sits in the repository root (the classic convention:
#      craftbukkit-*.jar, gitignored), it is used as-is.
#   2. Otherwise the jar is extracted from this repository's own git history.
#      The jars were tracked until commit 8e7f60d3 ("stop tracking
#      offline-build jars"), so any full clone still carries the bytes; a
#      shallow clone does not, and this script fails loudly in that case.
#
# Why they are installed with a minimal POM:
#   Each CraftBukkit jar embeds its original BuildTools build POM in
#   META-INF/maven/, and modern maven-install-plugin installs that embedded
#   POM instead of generating one. That POM declares a compile dependency on
#   org.spigotmc:minecraft-server:<mcver>-SNAPSHOT, an artifact that no
#   longer exists in any public Maven repository (verified 2026-11-08:
#   hub.spigotmc.org snapshots -> 404, repo.codemc.io NMS repo only carries
#   1.17+, jitpack/codemc-public cannot resolve it either). Compilation of
#   the NMS modules never uses minecraft-server: every class they need
#   (net.minecraft.server.<nms_pkg>, org.bukkit.craftbukkit.<nms_pkg>) is
#   already inside the CraftBukkit jar. Installing a minimal POM therefore
#   removes only an unresolvable phantom dependency, nothing the build uses.
#
# The jars themselves are never committed at HEAD (see .gitignore).

set -e

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || true)"
if [ -z "$REPO_ROOT" ]; then
    echo "Error: must run inside a git checkout of this repository." >&2
    exit 1
fi
cd "$REPO_ROOT"

# SHA-256 of the jars as tracked at 8e7f60d3^ (the last commit containing
# them). Used only to verify jars extracted from git history; manually
# supplied root jars are accepted as-is.
sha256_craftbukkit_1_12="cacc54b134965bf7e7a42bf48daf9b154dc5abbf53700a60870ddc8e0c0bebca"
sha256_craftbukkit_1_8="22ee7b863a56ddbac1ebe2b022d8cf616cafb84197450ee5721c8279af3ab221"
sha256_craftbukkit_1_8_3="eb91da4554dc32f4339fd3522e79e442860d3d0ee48e9eccd08492b807d5ea82"
sha256_craftbukkit_1_8_8="71af4d5d7a5ecf26d1aff312fcb18e1897944bdd96424917e4c7cfdd584978f3"

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

# Materialize $1 (repo-root relative) from git history when absent.
ensure_jar() {
    local filename=$1
    local expected_sha=$2

    if [ -f "$filename" ]; then
        echo "Found $filename in repository root."
        return 0
    fi

    echo "$filename not present; extracting from git history..."
    local del_commit
    del_commit="$(git log -1 --format=%H --diff-filter=D -- "$filename" || true)"
    if [ -n "$del_commit" ] && git show "${del_commit}^:${filename}" > "$filename" 2>/dev/null; then
        :
    else
        local last_commit
        last_commit="$(git log -1 --format=%H --all -- "$filename" || true)"
        if [ -n "$last_commit" ] && git show "${last_commit}:${filename}" > "$filename" 2>/dev/null; then
            :
        else
            rm -f "$filename"
            echo "Error: cannot obtain $filename." >&2
            echo "This checkout has no git history containing it (shallow clone?)." >&2
            echo "Place $filename in the repository root manually, then re-run." >&2
            exit 1
        fi
    fi

    local actual_sha
    actual_sha="$(sha256_of "$filename")"
    if [ "$actual_sha" != "$expected_sha" ]; then
        rm -f "$filename"
        echo "Error: $filename extracted from git history failed checksum." >&2
        echo "  expected sha256: $expected_sha" >&2
        echo "  actual sha256:   $actual_sha" >&2
        exit 1
    fi
    echo "Extracted $filename (sha256 verified)."
}

# Install $1 as org.bukkit:craftbukkit:$2 using a minimal POM (see header).
install_craftbukkit() {
    local filename=$1
    local artifact_version=$2

    local minimal_pom
    minimal_pom="$(mktemp)"
    cat > "$minimal_pom" <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.bukkit</groupId>
  <artifactId>craftbukkit</artifactId>
  <version>${artifact_version}</version>
</project>
EOF

    echo "Installing org.bukkit:craftbukkit:${artifact_version} from ${filename}..."
    mvn -B -N install:install-file -Dfile="$filename" -DpomFile="$minimal_pom" \
        --no-transfer-progress
    rm -f "$minimal_pom"
}

ensure_jar "craftbukkit-1.12.jar" "$sha256_craftbukkit_1_12"
ensure_jar "craftbukkit-1.8-R0.1-SNAPSHOT.jar" "$sha256_craftbukkit_1_8"
ensure_jar "craftbukkit-1.8.3.jar" "$sha256_craftbukkit_1_8_3"
ensure_jar "craftbukkit-1.8.8.jar" "$sha256_craftbukkit_1_8_8"

install_craftbukkit "craftbukkit-1.12.jar" "1.12-R0.1-SNAPSHOT"
install_craftbukkit "craftbukkit-1.8-R0.1-SNAPSHOT.jar" "1.8-R0.1-SNAPSHOT"
install_craftbukkit "craftbukkit-1.8.3.jar" "1.8.3-R0.1-SNAPSHOT"
install_craftbukkit "craftbukkit-1.8.8.jar" "1.8.8-R0.1-SNAPSHOT"

echo "All CraftBukkit dependencies installed."
