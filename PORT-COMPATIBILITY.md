# Forge 1.20.1 optional compatibility

Policy confirmed by the user on 2026-10-30: inspect the target mod's 1.20.1 Git
source before adapting its interface. Drop an integration when no 1.20.1 mod
exists. A missing repository URL or missing dependency is not proof that the
mod has no 1.20.1 version.

## Source pins inspected on 2026-10-30

| Mod | Source branch and commit | Current work |
| --- | --- | --- |
| Mekanism Extras | [lostmyself8/Mekanism-Extras, 1.20.1](https://github.com/lostmyself8/Mekanism-Extras/tree/624e825219c70614fee39cbab61717733c4eff30) | Corrected sorting-tab import and factory-tier reflection package. Binary 8247562 matches upstream EME dependency catalog. The pinned 1.20.1 API compiles; runtime verification remains. |
| Evolved Mekanism Extras | [masyumero/EvolvedMekanismExtras, forge1.20.1](https://github.com/masyumero/EvolvedMekanismExtras/tree/5c1a6d35c33ece12640474ee72bc4e05bcefd2a5) | Correct superclass is TileEntityItemStackToItemStackEMExtraFactory. Added its Evolved Mekanism compile dependency from upstream gradle/forge.versions.toml. The pinned 1.20.1 API compiles; runtime verification remains. |
| Evolved Mekanism | [iglee42/EvolvedMekanism, 1.20.1](https://github.com/iglee42/EvolvedMekanism/tree/8bb504d1d5a5ed01802c7943fdba0786aac897fb) | Retain. EME catalog pins curse.maven:evolved-mekanism-1230085:7117024 for compilation. |
| JDTE | [rulanup/JDTE, forge-1.20.1](https://github.com/rulanup/JDTE/tree/ac1d813d010b6844746534a5a12c0c5b3251ff92) | Retain. Upstream declares Minecraft 1.20.1, Java 17 and Forge 47.4.10. Exact two-method CoalescedAcceleratedMachine interface lives in a compile-only source set; it is not shipped as our runtime implementation. |
| Oculus | [Asek3/Oculus, 1.20.1-new](https://github.com/Asek3/Oculus/tree/b3b278134f719afe32ba8b6b5d3a93f052175afc) | Retain public IrisApi.getInstance/isShaderPackInUse reflection bridge and deferred glow rendering. Removed the incompatible Iris 1.21 SkipList mixin: Oculus does not expose that API. Visual validation pending. |
| Mekanism Empowered | [Lapis256/MekanismEmpowered, 1.20.1](https://github.com/Lapis256/MekanismEmpowered/tree/44649684c197114081d106a4729ad2c701aa9ba4) | Retained behind optional guards; runtime verification remains. The old source URL was stale. |
| AE2 | [AppliedEnergistics/Applied-Energistics-2, forge/1.20.1](https://github.com/AppliedEnergistics/Applied-Energistics-2/tree/forge/1.20.1) | Retained with 1.20.1 storage/network APIs and FE-to-FloatingLong conversion; runtime verification remains. |

## Dropped active integrations

- Mek Energistics: upstream [gradle.properties](https://github.com/beipuo/Mek-Energistics/blob/main/gradle.properties)
  targets NeoForge 1.21.1, the repository branch list contains main and a fix
  branch, and the [publisher page](https://www.curseforge.com/minecraft/mc-mods/mek-energistics)
  lists only NeoForge 1.21.1. The three mixin registrations and plugin detection
  were removed. Sources were moved to port-disabled, outside all source sets.
- Iris 1.21's SkipList-specific constructor hook is archived in port-disabled.
  The 1.20.1 shader bridge uses Oculus's actual public API instead.

## Runtime verification pending

Applied Flux, Jade, JEI, KubeJS 6, Building Gadgets 2 and Create-related AE2
hooks were checked against their 1.20.1 sources and adapted behind
`MixinConfigPlugin` gates; they are optional and must still be verified in a
real game session with each mod installed. Existing source presence is not
runtime validation.

Production code compiles as a whole under Java 17/Forge 1.20.1, and
`jarJar reobfJarJar` produces the reobfuscated jar-in-jar artifact at
`build/libs/productivebeesgenesis-1.0.10-forge-1.20.1-all.jar`.
See README-FORGE.md for bootstrap instructions and the Forge CI workflow for
repeatable packaging. Game startup and runtime behavior are not claimed here.

The legacy JUnit source tree is retained but its dependencies and tests have
not been migrated as part of this production packaging snapshot. The full
`build`/`test` tasks are not claimed to pass.

KubeJS moved to `kube-mods/kubejs`; its 1.20.1 source branch is `2001`, commit
`ba142541dcc1d230383f4a55e38dd92ff10d1029`. The Genesis adapter was migrated to
that API (plugin class, EventJS, `Map<ResourceLocation, Recipe<?>>` injection
hook, recipe JSON/NBT formats); runtime behavior remains unverified.

Mekanism Empowered Unleashed moved to
`zaixiayesheng/MekanismEmpoweredUnleashed`; it has a `1.20.1` branch at
`1ab3ff54c56ebf5f61595c1be99e06bacf9e08e8`. Do not discard it based on the
stale hyphenated repository URL or the original Unleashed version list.
