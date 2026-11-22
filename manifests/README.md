# Local manifests

`*.xml` here are copied into `.repo/local_manifests/` by `scripts/build/buildserver-pipeline.sh` on
every build (files that still contain `OWNER` are skipped). `flavors/<flavour>.xml` are copied only
when that flavour is built (`HIBREAK_FLAVORS`, docs/building.md): `flavors/gapps.xml`
brings in MindTheGapps. The device and vendor trees are not synced: the build scripts upload
them (`scripts/ci/hcloud-build.sh`).
