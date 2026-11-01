# Forge 1.20.1 port snapshot

This branch contains the Forge 1.20.1 backport of the upstream 1.2.1 feature set,
including the resource bee editor, resource production and optional integrations.
The packaged version is `1.2.1-forge-1.20.1`.

The release JAR and SHA-256 are tracked under [releases/1.2.1](releases/1.2.1/).
This release also includes editor smoothing/Chinese translation improvements,
Forge recipe publication/recovery fixes and the Mekanism Extras factory accessor fix.

## Build

Use JDK 17 and Python 3.11 or newer. Restore the compile inputs, then build,
reobfuscate and verify the jar-in-jar artifact:

```sh
python tools/prepare_forge_dependencies.py
./gradlew --no-daemon jarJar reobfJarJar
python tools/verify_forge_artifact.py
```

On Windows use `gradlew.bat`. The output is
`build/libs/productivebeesgenesis-1.2.1-forge-1.20.1-all.jar`.
Do not distribute an artifact before `reobfJarJar` finishes.

The new optional integrations additionally require six compile-only inputs, listed
with SHA-256 hashes in `tools/forge_optional_compile_inputs.json`. Obtain the matching
AE2 LT, Ars Nouveau, PneumaticCraft, ProjectE and locally built Useless Mod 1.4.6 /
Useless Core 1.0.17 JARs, then stage them before building:

```sh
python tools/prepare_optional_compile_inputs.py /path/to/mods /path/to/useless/build/libs /path/to/useless-core/build/libs
```

These dependencies remain local, excluded from Git and from the Genesis JAR.
Useless integration targets the local Forge 1.20.1 project's actual API.
The existing CI bootstrap restores the base inputs only; a clean CI worker also
needs the six pinned optional inputs. A passing hosted CI run is not claimed for
this source snapshot. The release JAR was built and reobfuscated locally.

JDTE's exact 1.20.1 interface is compiled in a separate compile-only source set
and is never bundled. Java 17 and Forge 47.4.0 remain the build targets.

## Verification and scope

- Production compilation and `jarJar reobfJarJar` have passed locally with
  Forge 47.4.0, Mekanism 10.4.16.80 and Productive Bees 12.6.0.
- Recipe/tag resource paths, NBT serialization, Forge networking, GUI APIs and
  optional integration interfaces have been backported to 1.20.1.
- The legacy JUnit source tree is retained. The packaging command above does
  not compile or run it; `build`/`test` are not claimed to pass.
- Successful packaging is not an in-game compatibility test. Optional mod
  combinations and gameplay still require runtime verification.

CI checks Forge metadata, Java 17 bytecode, MixinExtras, project license notices,
and exclusion of JDTE API classes. Its job has a five-minute time limit; a timeout
is not a successful build. The old NeoForge CurseForge workflow and unused
NeoForge metadata template have been removed from this branch. Forge publishing
requires a separate, reviewed release workflow after runtime acceptance.

See [PORT-COMPATIBILITY.md](PORT-COMPATIBILITY.md) for source references and
integration decisions. [README.md](README.md) and [README_zh.md](README_zh.md)
are the English and Chinese branch entry points. Target `forge/1.20.1` for
subsequent contributions.
