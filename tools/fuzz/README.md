# tools/fuzz

Mutation fuzzer for the `core/protocol` decoders (testing §5 "Fuzz" row; WP3, long runs in WP11).

Targets: `frame` (frame codec and streaming `FrameReader`), `control` (`ControlCodec`), `chunk` (chunk header and
frame), `bundle` (bundle index), `stream-open` (`StreamOpen` frames). The seed corpus is the golden vectors of
`core/protocol` (compiled in from `core/protocol/src/commonTest/.../golden`). Each input must decode or raise
`ProtocolException`, stay within the allocation limit the decoder declares (measured with the HotSpot per-thread
allocation counter), decode in under 2 s, and, when accepted, satisfy the target's round-trip property.

```sh
./gradlew :tools:fuzz:test                      # CI smoke: fixed seed, 100,000 inputs per target (about 10 s)
./gradlew :tools:fuzz:test -Pdrop.nightly=true  # nightly: 1 minute per target, seed from the date
./gradlew :tools:fuzz:test -Pdrop.fuzz.seed=42  # replay a seed
./gradlew :tools:fuzz:run --args="--seconds 600 --target control"
```

A failure prints the target, the broken property and the input as hex.
