#!/usr/bin/env bash
# Downloads real FlatBuffers schemas into samples/ (directory layout of the upstream repos is kept so that
# `include` directives resolve) and writes samples/sources.txt.
#   google/flatbuffers  https://github.com/google/flatbuffers
#   apache/arrow        https://github.com/apache/arrow  (the Arrow IPC format is defined in FlatBuffers)
set -eu
cd "$(dirname "$0")"
FB=https://raw.githubusercontent.com/google/flatbuffers/master
ARROW=https://raw.githubusercontent.com/apache/arrow/main/format
# <path in samples/> <url>
FILES="
flatbuffers/samples/monster.fbs                          $FB/samples/monster.fbs
flatbuffers/tests/monster_test.fbs                       $FB/tests/monster_test.fbs
flatbuffers/tests/include_test/include_test1.fbs         $FB/tests/include_test/include_test1.fbs
flatbuffers/tests/include_test/sub/include_test2.fbs     $FB/tests/include_test/sub/include_test2.fbs
flatbuffers/tests/arrays_test.fbs                        $FB/tests/arrays_test.fbs
flatbuffers/tests/optional_scalars.fbs                   $FB/tests/optional_scalars.fbs
flatbuffers/tests/union_vector/union_vector.fbs          $FB/tests/union_vector/union_vector.fbs
flatbuffers/reflection/reflection.fbs                    $FB/reflection/reflection.fbs
arrow/Schema.fbs                                         $ARROW/Schema.fbs
arrow/Message.fbs                                        $ARROW/Message.fbs
arrow/File.fbs                                           $ARROW/File.fbs
arrow/Tensor.fbs                                         $ARROW/Tensor.fbs
arrow/SparseTensor.fbs                                   $ARROW/SparseTensor.fbs
"
# The page of a file in its repository, for a reader: raw.githubusercontent.com gives the bare text.
page() {
    case "$1" in
        https://raw.githubusercontent.com/*)
            local p="${1#https://raw.githubusercontent.com/}"
            local owner="${p%%/*}"; p="${p#*/}"
            local repo="${p%%/*}"; p="${p#*/}"
            echo "https://github.com/$owner/$repo/blob/$p" ;;
        *) echo "$1" ;;
    esac
}
mkdir -p samples
{
    echo "# Where every sample comes from: <path in samples/> <page of the original>. Written by fetch-samples.sh;"
    echo "# the converter links these pages in the headers of the descriptions."
    while read -r n url; do
        [ -n "$n" ] && printf '%-56s %s\n' "$n" "$(page "$url")"
    done <<< "$FILES"
} > samples/sources.txt

while read -r n url; do
    [ -z "$n" ] && continue
    mkdir -p "samples/$(dirname "$n")"
    curl -sSf -o "samples/$n" "$url"
    echo "  $n"
done <<< "$FILES"
echo "done"
