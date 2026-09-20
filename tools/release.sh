#!/usr/bin/env bash
# volzz を GitHub Release にする。commit と push とタグ付けまで済ませてから使う。
#   使い方:  bash tools/release.sh v11 "v11: 見出し" notes.md
#            bash tools/release.sh v11 "v11: 見出し"          （本文は標準入力から）
#
# この PC には gh が入っていないので、GitHub API を直接叩く。認証は Git が使うのと
# 同じ資格情報（Git Credential Manager）を借りる。トークンは curl の設定ファイルに
# 直接書き込むだけで、変数にも画面にも出さない。設定ファイルは終了時に必ず消す。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TAG="${1:?タグを渡すこと（例: v11）}"
TITLE="${2:?題を渡すこと}"
NOTES_FILE="${3:-}"
APK="$ROOT/volzz.apk"
REPO="iosxi/volzz"

[ -f "$APK" ] || { echo "volzz.apk がありません。先に assembleRelease してコピーすること" >&2; exit 1; }

# タグが GitHub 側にあることを確かめる。無いまま投げると、その場でタグが生えてしまう。
git ls-remote --tags origin "refs/tags/$TAG" | grep -q "refs/tags/$TAG" \
    || { echo "タグ $TAG が push されていません。先に git push origin $TAG" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
chmod 700 "$TMP"

if [ -n "$NOTES_FILE" ]; then
    cp "$NOTES_FILE" "$TMP/notes.md"
else
    cat > "$TMP/notes.md"          # 標準入力（ヒアドキュメント）から本文を受ける
fi

# --- 認証 -------------------------------------------------------------------
printf 'protocol=https\nhost=github.com\n\n' \
    | git credential fill \
    | sed -n 's/^password=\(.*\)$/header = "Authorization: token \1"/p' > "$TMP/curlrc"
[ -s "$TMP/curlrc" ] || { echo "GitHub の資格情報を取り出せませんでした" >&2; exit 1; }
{
    echo 'header = "Accept: application/vnd.github+json"'
    echo 'header = "X-GitHub-Api-Version: 2022-11-28"'
    echo 'user-agent = "volzz-release"'
    echo 'silent'
    echo 'show-error'
} >> "$TMP/curlrc"

# --- リリースを作る ---------------------------------------------------------
python - "$TAG" "$TITLE" "$TMP/notes.md" > "$TMP/req.json" <<'PY'
import io, json, sys
tag, title, notes = sys.argv[1], sys.argv[2], sys.argv[3]
print(json.dumps({"tag_name": tag, "name": title,
                  "body": io.open(notes, encoding='utf-8').read()}))
PY

curl -K "$TMP/curlrc" -X POST "https://api.github.com/repos/$REPO/releases" \
     -H "Content-Type: application/json" --data-binary "@$TMP/req.json" > "$TMP/release.json"

UPLOAD="$(python -c "import json,sys; r=json.load(open(sys.argv[1], encoding='utf-8')); \
print(r.get('upload_url','').split('{')[0])" "$TMP/release.json")"
if [ -z "$UPLOAD" ]; then
    echo "リリースを作れませんでした:" >&2
    python -c "import json,sys; print(json.load(open(sys.argv[1], encoding='utf-8')).get('message','?'))" \
        "$TMP/release.json" >&2
    exit 1
fi

# --- APK を添える -----------------------------------------------------------
curl -K "$TMP/curlrc" -X POST "$UPLOAD?name=volzz.apk" \
     -H "Content-Type: application/vnd.android.package-archive" \
     --data-binary "@$APK" > "$TMP/asset.json"

python - "$TMP/asset.json" "$TMP/release.json" <<'PY'
import io, json, sys
asset = json.load(io.open(sys.argv[1], encoding='utf-8'))
rel = json.load(io.open(sys.argv[2], encoding='utf-8'))
if asset.get('state') != 'uploaded':
    print("APK を添えられませんでした: %s" % asset.get('message', asset), file=sys.stderr)
    sys.exit(1)
print("APK を添えました: %s (%d bytes)" % (asset['name'], asset['size']))
print("できました: %s" % rel['html_url'])
PY
