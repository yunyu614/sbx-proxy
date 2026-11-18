#!/bin/sh
# 组装可安装的模块 zip（需要 module/sing-box 与 module/tool.dex 已就绪）
set -e
cd "$(dirname "$0")/.."
python3 - <<'PY'
import zipfile, os, stat
z=zipfile.ZipFile("SBX-module.zip","w",zipfile.ZIP_DEFLATED)
for root,_,files in os.walk("module"):
    for f in files:
        p=os.path.join(root,f); arc=os.path.relpath(p,"module")
        zi=zipfile.ZipInfo(arc); zi.compress_type=zipfile.ZIP_DEFLATED
        zi.external_attr=((0o755 if (f.endswith(".sh") or f=="sing-box") else 0o644)|stat.S_IFREG)<<16
        z.writestr(zi, open(p,"rb").read())
z.close(); print("SBX-module.zip created")
PY
