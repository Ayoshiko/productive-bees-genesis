# Forge 1.20.1 port snapshot

This branch contains the locally compiled Productive Bees Genesis Forge port,
version `1.0.10-forge-1.20.1`. It replaces the NeoForge source/build setup that
was previously present on `forge/1.20.1`; it does not claim to include every
feature from the newer NeoForge 1.2.1 editor.

## Build

Use JDK 17. Restore the compile inputs, then build and reobfuscate the
jar-in-jar artifact:

```sh
python tools/prepare_forge_dependencies.py
./gradlew jarJar reobfJarJar
```

On Windows use `gradlew.bat`. The output is
`build/libs/productivebeesgenesis-1.0.10-forge-1.20.1-all.jar`.
Do not distribute an artifact before `reobfJarJar` finishes.

Gradle resolves the remaining pinned dependencies from their Maven repositories.
The bootstrap script extracts ProductiveLib 0.0.4 from Productive Bees 12.6.0
and downloads MixinExtras common/Forge 0.5.5; all three files are checked against pinned
SHA-256 hashes and remain excluded from Git. JDTE's exact 1.20.1 interface is
compiled in a separate compile-only source set and is not bundled.

## Verification and scope

- Production compilation and `jarJar reobfJarJar` have passed locally with
  Forge 47.4.0, Mekanism 10.4.16.80 and Productive Bees 12.6.0.
- Recipe/tag resource paths, NBT serialization, Forge networking, GUI APIs and
  optional integration interfaces have been backported to 1.20.1.
- The legacy JUnit source tree is retained. The packaging command above does
  not compile or run it; `build`/`test` are not claimed to pass.
- Successful packaging is not an in-game compatibility test. Optional mod
  combinations and gameplay still require runtime verification.

See [PORT-COMPATIBILITY.md](PORT-COMPATIBILITY.md) for source references and
integration decisions. The existing main README documents the upstream
NeoForge edition; follow this file for the Forge build.
