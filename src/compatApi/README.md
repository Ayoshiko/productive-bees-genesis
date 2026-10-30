# Compile-only upstream API sources

`jdte/com/jdte/common/blockentities/CoalescedAcceleratedMachine.java` is an unchanged
copy of the MIT-licensed JDTE Forge 1.20.1 interface, not a locally invented API.

- Repository: https://github.com/rulanup/JDTE
- Branch: `forge-1.20.1`
- Commit: `ac1d813d010b6844746534a5a12c0c5b3251ff92`
- Source: https://github.com/rulanup/JDTE/blob/ac1d813d010b6844746534a5a12c0c5b3251ff92/src/main/java/com/jdte/common/blockentities/CoalescedAcceleratedMachine.java

The separate `jdteApi` source set is used only on the main compile classpath.
The runtime interface belongs to JDTE and must not be bundled in Genesis.
MixinConfigPlugin gates all five JDTE interface mixins on JDTE being installed.

Runtime compatibility still requires testing with a Forge 1.20.1 JDTE build.
