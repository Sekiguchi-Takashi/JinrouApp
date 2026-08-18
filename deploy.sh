#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")"
TOKEN=$(git config --global github.token)
GHUSER=Sekiguchi-Takashi
REPO=JinrouApp
if [ ! -d .git ]; then git init -b main; fi
git remote remove origin 2>/dev/null
git remote add origin "https://${GHUSER}:${TOKEN}@github.com/${GHUSER}/${REPO}.git"
rm -f .github/workflows/build.yml
git add -A
git commit -m "${1:-update}"
git pull --rebase origin main
git push -u origin main
if [ "$2" = "notag" ]; then printf 'pushed (no tag)\n'; exit 0; fi
git fetch --tags origin
LATEST=$(git tag --list 'v*' | sort -V | tail -1)
NEXT=$(printf '%s' "$LATEST" | awk '/^v[0-9]+\.[0-9]+\.[0-9]+$/ { split($0,a,"."); sub("v","",a[1]); print "v" a[1] "." a[2] "." a[3]+1; next } /^v[0-9]+\.[0-9]+$/ { split($0,a,"."); sub("v","",a[1]); print "v" a[1] "." a[2] ".1"; next } /^v[0-9]+$/ { sub("v","",$0); print "v" $0+1; next } { print "v1.0.0" }')
if [ -z "$NEXT" ]; then NEXT=v1.0.0; fi
git tag "$NEXT"
git push origin "$NEXT"
printf 'pushed and tagged %s\n' "$NEXT"
