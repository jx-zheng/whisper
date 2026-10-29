# golden-gen

One-shot generator for `src/test/resources/golden/`. Not part of the Maven build.

It was run inside a Whisper2 checkout at `523e838` (MIT), with a temporary
exported wrapper `stego.PublicLayoutIndices` around unexported `publicLayout`.
The committed JSON is what the Java tests read. Whisper does not need Whisper2
to build, test, or run.

```
# from a Whisper2 tree that exports PublicLayoutIndices
go run ./cmd/golden-gen <whisper2-root> <out-dir>
```

`main.go` in this directory is that program, kept so the vector format stays
explained next to the JSON. `null` in a cost array means +Inf.
