#!/bin/sh
# Assembles the CLI's release files from a built cli/target (mvn -pl cli -am package):
#   <out>/mcpgp-cli-<version>.zip   bin/mcpgp, bin/mcpgp.cmd, lib/mcpgp-cli.jar, LICENSE, README.md
#   <out>/mcpgp-cli-<version>.jar   the same jar, for java -jar
# Usage: cli/src/dist/assemble.sh <version> <out>   (from the repository root)
set -eu
version=$1
out=$2
jar=cli/target/mcpgp-cli-$version-exec.jar
[ -f "$jar" ] || { echo "no $jar: build it first (mvn -pl cli -am package -DskipTests)" >&2; exit 1; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
dir=$work/mcpgp-cli-$version
mkdir -p "$dir/bin" "$dir/lib" "$out"
cp cli/src/dist/bin/mcpgp cli/src/dist/bin/mcpgp.cmd "$dir/bin/"
chmod 755 "$dir/bin/mcpgp"
cp "$jar" "$dir/lib/mcpgp-cli.jar"
cp LICENSE "$dir/"
# the specs and guides the tool's messages and generated files refer to (docs/spec/..., docs/guides/...)
mkdir -p "$dir/docs"
cp -R docs/spec docs/guides "$dir/docs/"
cp docs/concepts.md docs/security.md docs/deployment.md "$dir/docs/"
cat > "$dir/README.md" <<README
# mcpgp $version

The mcpgp command line. Needs Java 21 or newer.

    bin/mcpgp --help

Put \`bin/\` on your PATH (or link \`bin/mcpgp\` into it). Documentation is in \`docs/\`
(\`docs/spec/cli.md\` for this command, \`docs/guides/ci-pipeline.md\` for pipelines) and at
https://github.com/bitboyro/mcpgp/blob/v$version/docs/spec/cli.md
README
out=$(cd "$out" && pwd)
(cd "$work" && zip -qr -X "$out/mcpgp-cli-$version.zip" "mcpgp-cli-$version")
cp "$jar" "$out/mcpgp-cli-$version.jar"
echo "wrote $out/mcpgp-cli-$version.zip and $out/mcpgp-cli-$version.jar"
