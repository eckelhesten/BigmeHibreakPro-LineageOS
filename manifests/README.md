# Local manifests

`scripts/build/buildserver-pipeline.sh` copies `*.xml` here into `.repo/local_manifests/` on
every build (files that still contain `OWNER` are skipped); there are none at the moment.
`flavors/<flavour>.xml` are copied only when that flavour is built (`HIBREAK_FLAVORS`,
docs/building.md): `flavors/gapps.xml` brings in MindTheGapps. The device and vendor trees are not synced: the build scripts upload
them (`scripts/ci/hcloud-build.sh`).
