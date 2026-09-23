#!/usr/bin/env bash
# Downloads real FlatBuffers schemas into samples/ (directory layout of the upstream repos is kept so that
# `include` directives resolve).
#   google/flatbuffers  https://github.com/google/flatbuffers
#   apache/arrow        https://github.com/apache/arrow  (the Arrow IPC format is defined in FlatBuffers)
set -eu
cd "$(dirname "$0")"
FB=https://raw.githubusercontent.com/google/flatbuffers/master
ARROW=https://raw.githubusercontent.com/apache/arrow/main/format

get() { mkdir -p "samples/$(dirname "$2")"; curl -sSf -o "samples/$2" "$1" && echo "  $2"; }

echo "google/flatbuffers:"
get $FB/samples/monster.fbs                              flatbuffers/samples/monster.fbs
get $FB/tests/monster_test.fbs                           flatbuffers/tests/monster_test.fbs
get $FB/tests/include_test/include_test1.fbs             flatbuffers/tests/include_test/include_test1.fbs
get $FB/tests/include_test/sub/include_test2.fbs         flatbuffers/tests/include_test/sub/include_test2.fbs
get $FB/tests/arrays_test.fbs                            flatbuffers/tests/arrays_test.fbs
get $FB/tests/optional_scalars.fbs                       flatbuffers/tests/optional_scalars.fbs
get $FB/tests/union_vector/union_vector.fbs              flatbuffers/tests/union_vector/union_vector.fbs
get $FB/reflection/reflection.fbs                        flatbuffers/reflection/reflection.fbs

echo "apache/arrow:"
get $ARROW/Schema.fbs                                    arrow/Schema.fbs
get $ARROW/Message.fbs                                   arrow/Message.fbs
get $ARROW/File.fbs                                      arrow/File.fbs
get $ARROW/Tensor.fbs                                    arrow/Tensor.fbs
get $ARROW/SparseTensor.fbs                              arrow/SparseTensor.fbs
echo "done"
