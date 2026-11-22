#!/bin/bash
# Runs ON the build server: shallow-fetch projects that `repo sync` failed on (e.g. HTTP 429 from AOSP),
# then check them out locally. Usage: buildserver-fix-failed.sh path1 path2 ...
cd /srv/lineage || exit 1
tmp_manifest=$(mktemp)
trap 'rm -f "$tmp_manifest"' EXIT
repo manifest -o "$tmp_manifest" >/dev/null 2>&1 || { echo "repo manifest failed" >&2; exit 1; }
for p in "$@"; do
  rem="" name="" url="" rev=""
  IFS=$'\t' read -r rem name url rev < <(python3 - "$tmp_manifest" "$p" <<'PY'
import sys, xml.etree.ElementTree as ET
r = ET.parse(sys.argv[1]).getroot()
remotes = {x.get("name"): x.get("fetch") for x in r.findall("remote")}
remrev = {x.get("name"): x.get("revision") for x in r.findall("remote")}
d = r.find("default"); drem = d.get("remote"); drev = d.get("revision")
for pr in r.iter("project"):
    if (pr.get("path") or pr.get("name")) == sys.argv[2]:
        rem = pr.get("remote", drem); fetch = remotes[rem].rstrip("/")
        if fetch.startswith(".."): fetch = "https://github.com"  # manifest URL is github.com/LineageOS/android
        rev = pr.get("revision") or remrev.get(rem) or drev or ""
        print(rem, pr.get("name"), fetch + "/" + pr.get("name"), rev, sep="\t")
        break
PY
)
  [ -n "$rev" ] || { echo "NOTFOUND $p"; continue; }
  if [[ "$rev" =~ ^[0-9a-fA-F]{40}$ ]]; then
    refspec="$rev"
  elif [[ "$rev" == refs/tags/* ]]; then
    refspec="+$rev:$rev"
  elif [[ "$rev" == refs/heads/* ]]; then
    branch="${rev#refs/heads/}"
    refspec="+$rev:refs/remotes/$rem/$branch"
  elif [[ "$rev" == refs/* ]]; then
    refspec="+$rev:$rev"
  else
    refspec="+refs/heads/$rev:refs/remotes/$rem/$rev"
  fi
  gitdir=".repo/projects/$p.git"; [ -d "$gitdir" ] || gitdir=".repo/project-objects/$name.git"
  if [ ! -d "$gitdir" ]; then
    echo "MISSING_GITDIR $p ($gitdir)"
    continue
  fi
  ok=0
  for t in 1 2 3 4; do
    if timeout 1200 git --git-dir="$gitdir" fetch --depth=1 --no-tags "$url" "$refspec" >/dev/null 2>&1; then
      ok=1
      break
    fi
    [ "$t" -lt 4 ] && sleep 60
  done
  echo "$([ "$ok" = 1 ] && echo FETCHED || echo FAILED) $p ($url @ $rev)"
done
repo sync -l -c "$@" > /srv/fix-checkout.log 2>&1 && echo CHECKOUT_OK || { echo CHECKOUT_FAILED; tail -5 /srv/fix-checkout.log; exit 1; }
