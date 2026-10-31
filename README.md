# Productive Bees Genesis — Forge 1.20.1

**Languages:** [English](README.md) · [中文](README_zh.md)

This is the Forge **1.20.1** maintenance branch of Productive Bees Genesis, a
Productive Bees and Mekanism addon with the Myriad Creations Bee, Mekanism
apiaries and centrifuges, and optional AE2 integration.

| Component | Branch baseline |
| --- | --- |
| Branch | `forge/1.20.1` |
| Genesis snapshot | `1.0.10-forge-1.20.1` |
| Minecraft | `1.20.1` |
| Forge | `47.4.0` |
| Java | `17` |
| Productive Bees | `1.20.1-12.6.0` |
| Mekanism | `1.20.1-10.4.16.80` |

The port was contributed by [mybong9](https://github.com/mybong9) in
[PR #13](https://github.com/Ayoshiko/productive-bees-genesis/pull/13).
Ayoshiko maintains the upstream project and assists with this branch.
The independent [bpkiu port](https://github.com/bpkiu/Productive-Bees-Genesis-1.20.1)
is a separate repository and code lineage; its releases are not this branch's artifacts.

## Build

Use JDK 17 and Python 3.11 or newer:

```sh
python tools/prepare_forge_dependencies.py
./gradlew --no-daemon jarJar reobfJarJar
python tools/verify_forge_artifact.py
```

On Windows, replace `./gradlew` with `.\gradlew.bat`.
The distributable is `build/libs/productivebeesgenesis-1.0.10-forge-1.20.1-all.jar`.
Distribute it only after reobfuscation and artifact verification finish.
See [README-FORGE.md](README-FORGE.md) for dependency preparation and packaging details.

## Status and compatibility

This is a compiled port snapshot. It does not claim feature parity with the
newer NeoForge 1.2.1 editor or extended resource authoring system.
The retained legacy JUnit sources have not been migrated; `build` and `test`
are not the validated packaging commands.

Game startup, client rendering, optional mod combinations, machine behavior,
and save restoration still need runtime verification. Do not treat a successful
CI artifact as a stable release or as evidence of save compatibility with another port.
[PORT-COMPATIBILITY.md](PORT-COMPATIBILITY.md) records the port's integration decisions
and remaining validation work. Automatic CurseForge publication is not configured
for this Forge branch.

The [NeoForge 1.21.1 branch](https://github.com/Ayoshiko/productive-bees-genesis/tree/main-neo/1.21.1)
contains the separate modern edition and its documentation. Its dependencies,
release artifacts, editor features and runtime evidence do not apply to this branch.

## Contributing

Target **`forge/1.20.1`** for Forge fixes and translations. Contributors can fork
this repository and open a pull request against that branch. Include the Java,
Forge and dependency versions, the packaging result, and which runtime scenarios
were actually tested. Report issues with **Forge 1.20.1** in the title.

Branch updates use reviewed PRs, including contributions from the Forge maintainer.
The Forge platform policy and Java 17 build must pass; approvals are dismissed after
new changes and review conversations must be resolved. CI, publishing, build inputs,
and platform metadata need Ayoshiko's review. Other branches and release tags remain
owner-managed. External PR Actions may require maintainer approval before execution.

Changes shared with NeoForge should be adapted and validated for each loader's API.
Do not merge the full Forge source tree into `main-neo/1.21.1` or copy a NeoForge
build setup over this branch.

## License

Project code is provided under the [MIT License](LICENSE).
See [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for third-party notices and
[src/compatApi/README.md](src/compatApi/README.md) for the compile-only JDTE interface.
