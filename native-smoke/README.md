# native-smoke

Executable proof of nightjar's GraalVM native-image support — not published.

A plain `main()` exercises every native-sensitive code path (UUIDv7/SecureRandom,
relay scheduler threads, sync/async dispatch, retries, the data-migration
engine, classpath manifest discovery) and exits non-zero on any failure.

```bash
./gradlew :native-smoke:nativeRun     # AOT-compile + execute the binary
./gradlew :native-smoke:run           # same assertions on the JVM (fast sanity)
```

GraalVM is provisioned automatically through Gradle toolchains
(`vendor = GRAAL_VM` + `nativeImageCapable = true` + the foojay resolver) —
nothing to install. Native compilation takes a few minutes; this is
deliberately **not** part of `./gradlew build`.

## Known quirk

Gradle's toolchain extraction can materialize the `bin/native-image` symlink
as an empty file ("A problem occurred starting process ... native-image").
Repair once per downloaded toolchain:

```bash
G=~/.gradle/jdks/graalvm_community-*-amd64-linux*; \
ln -sf ../lib/svm/bin/native-image $G/bin/native-image
```
