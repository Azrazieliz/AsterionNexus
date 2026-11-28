#!/usr/bin/env python3
"""Use existing owner Windows signer and native Control transfer.
No rebuild, new key, token export, install, or changes to donor apps.
Wait only for the exact submitted source revision; refuse failed gates."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlparse
import zipfile

REPO = "Azrazieliz/AsterionNexus"
BRANCH = "work/nexus-completion-20261128b"
HEAD = os.environ["NEXUS_DELIVERY_SHA"]
assert re.fullmatch(r"[a-f0-9]{40}", HEAD)
CERT = "2469e78c998067cbf4346edc8acb771e3f7f993097fc569897f187899e70938a"
BASE = Path("D:/AsterionNexusBuild")
ROOT = BASE / "output" / ("delivery-" + HEAD[:12])
TOOLS = BASE / "toolchain"
OWNER = Path(os.environ["LOCALAPPDATA"]) / "AsterionNexus" / "signing"
CONTROL = Path("D:/AsterionControlRelease")
API = "https://api.github.com/repos/" + REPO
GATES = ("Nexus Android native compile and regression",
         "Nexus unified Android real acquisition acceptance",
         "Nexus complete model-inclusive release candidate")
ARTIFACT = "Nexus-complete-unsigned-owner-signing-candidate"

def log(name, value):
    print(name + "=" + str(value), flush=True)

def sha(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()

def run(args, **kw):
    return subprocess.run([str(x) for x in args], check=True, **kw)

def auth():
    # Use the established Git credential helper in memory only.
    env = os.environ.copy()
    env["GIT_TERMINAL_PROMPT"] = "0"
    cp = run(["git", "credential", "fill"],
             input=b"protocol=https\nhost=github.com\n\n",
             env=env, capture_output=True, timeout=40)
    v = dict(x.split("=", 1) for x in cp.stdout.decode().splitlines() if "=" in x)
    assert v.get("username") and v.get("password"), "Existing Git authentication unavailable"
    return "Basic " + base64.b64encode((v["username"] + ":" + v["password"]).encode()).decode()

def req(url, authorization=None):
    headers = {"Accept":"application/vnd.github+json","User-Agent":"AsterionNexus-owner-delivery"}
    if authorization:
        headers["Authorization"] = authorization
    return urllib.request.Request(url, headers=headers)

def api(path, authorization):
    with urllib.request.urlopen(req(API+path, authorization), timeout=80) as f:
        return json.load(f)

def gate(authorization):
    deadline = time.monotonic() + 3600
    last = None
    while time.monotonic() < deadline:
        latest = api("/branches/"+BRANCH.replace("/", "%2F"),authorization)["commit"]["sha"]
        assert latest == HEAD, "Source revision superseded; no delivery"
        runs = api("/actions/runs?head_sha="+HEAD+"&per_page=100",authorization)["workflow_runs"]
        chosen = {}
        for r in sorted(runs,key=lambda v:v["id"],reverse=True):
            if r["head_sha"]==HEAD and r["name"] in GATES and r["name"] not in chosen:
                chosen[r["name"]]=r
        state = [(n,chosen[n]["id"],chosen[n]["status"],chosen[n]["conclusion"])
                 if n in chosen else (n,None,"not-created",None) for n in GATES]
        if state != last:
            log("NEXUS_GATE",json.dumps(state));last=state
        for n,r in chosen.items():
            if r["status"]=="completed" and r["conclusion"]!="success":
                raise RuntimeError("Gate failed: "+n+" "+str(r["id"])+" "+str(r["conclusion"]))
        if len(chosen)==len(GATES) and all(r["conclusion"]=="success" for r in chosen.values()):
            return chosen[GATES[-1]]["id"], state
        time.sleep(30)
    raise TimeoutError("Exact source acceptance not completed within gate deadline")

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def obtain(authorization, run_id):
    artifacts=api("/actions/runs/"+str(run_id)+"/artifacts",authorization)["artifacts"]
    matches=[a for a in artifacts if a["name"]==ARTIFACT and not a.get("expired")]
    assert len(matches)==1, "Release artifact not uniquely available"
    a=matches[0]
    digest=a.get("digest","")
    assert re.fullmatch(r"sha256:[a-f0-9]{64}",digest), "Missing trusted archive digest"
    archive=ROOT/"candidate.zip"
    if not archive.is_file() or sha(archive)!=digest[7:]:
        assert shutil.disk_usage(ROOT).free>6*a["size_in_bytes"], "Insufficient D: space"
        endpoint=API+"/actions/artifacts/"+str(a["id"])+"/zip"
        try:
            response=urllib.request.build_opener(NoRedirect).open(req(endpoint,authorization),timeout=90)
            code,target=response.status,response.headers.get("Location")
        except urllib.error.HTTPError as e:
            code,target=e.code,e.headers.get("Location")
        assert code in (302,307) and target, "Missing artifact download redirect"
        u=urlparse(target)
        assert u.scheme=="https" and ((u.hostname or "").endswith(".blob.core.windows.net") or (u.hostname or "").endswith(".githubusercontent.com") or (u.hostname or "").endswith(".actions.githubusercontent.com")), "Untrusted artifact endpoint"
        part=archive.with_suffix(".part")
        with urllib.request.urlopen(req(target),timeout=180) as src,part.open("wb") as dst:
            shutil.copyfileobj(src,dst,4*1024*1024)
        assert sha(part)==digest[7:],"Archive digest mismatch"
        part.replace(archive)
    unsigned=ROOT/"app-release-unsigned.apk"
    with zipfile.ZipFile(archive) as z:
        def unique(name):
            matches=[p for p in z.namelist() if p.rsplit("/",1)[-1]==name]
            assert len(matches)==1,"Unexpected artifact layout: "+name
            return matches[0]
        receipt=json.loads(z.read(unique("nexus-release-receipt.json")))
        assert receipt["commit"]==HEAD and receipt["signing"]=="unsigned_private_owner_signing_required"
        part=unsigned.with_suffix(".part")
        with z.open(unique("app-release-unsigned.apk")) as src,part.open("wb") as dst:
            shutil.copyfileobj(src,dst,4*1024*1024)
        assert part.stat().st_size==receipt["bytes"] and sha(part)==receipt["sha256"],"APK digest mismatch"
        part.replace(unsigned)
    with zipfile.ZipFile(unsigned) as z:
        required={"assets/bundled_wd_vit_v3.zip","assets/bundled_ppocrv5_cjk.zip",
                  "lib/arm64-v8a/libnexus_sentencepiece.so","lib/arm64-v8a/libfilament-jni.so",
                  "assets/yt_dlp-2026.10.26-py3-none-any.whl"}
        for lang in ("ja","ko"):
            required.update("assets/translation-models/"+lang+"/"+n for n in
                ("manifest.json","encoder.onnx","decoder.onnx","source.spm","target.spm","vocab.json","config.json"))
        assert required <= set(receipt["model_components"]) and required <= set(z.namelist())
        assert all(z.getinfo(n).file_size>0 for n in required)
    log("NEXUS_VERIFIED_UNSIGNED",json.dumps({"sha256":receipt["sha256"],"bytes":receipt["bytes"],"source":HEAD}))
    return unsigned

def sign(unsigned):
    env=os.environ.copy()
    temp=TOOLS/"tmp"
    temp.mkdir(parents=True,exist_ok=True)
    env.update({"JAVA_HOME":str(TOOLS/"jbr"),"TEMP":str(temp),"TMP":str(temp),
                "JAVA_TOOL_OPTIONS":"-Djava.io.tmpdir="+str(temp)})
    buildtools=TOOLS/"android-sdk"/"build-tools"
    signer=next((x for x in (buildtools/"36.1.0"/"apksigner.bat",buildtools/"36.0.0"/"apksigner.bat") if x.is_file()),None)
    assert signer,"Existing signer unavailable"
    aapt=signer.parent/"aapt.exe"
    assert aapt.is_file()
    key=OWNER/"nexus-release.p12"
    secret=OWNER/"nexus-signing-password.txt"
    assert key.is_file() and secret.is_file(),"Existing Nexus signing identity unavailable; no replacement"
    env["NEXUS_OWNER_STORE_PASS"]=secret.read_text(encoding="utf-8").strip()
    package=run([aapt,"dump","badging",unsigned],env=env,capture_output=True,text=True,timeout=180).stdout
    assert "package: name='com.asterion.nexus'" in package,"Wrong package"
    signed=ROOT/("AsterionNexus-"+HEAD[:12]+".apk")
    part=signed.with_suffix(".part.apk")
    part.unlink(missing_ok=True)
    run([signer,"sign","--ks",key,"--ks-type","PKCS12","--ks-key-alias","nexus",
         "--ks-pass","env:NEXUS_OWNER_STORE_PASS","--key-pass","env:NEXUS_OWNER_STORE_PASS",
         "--v2-signing-enabled","true","--v3-signing-enabled","true","--out",part,unsigned],
        env=env,stdout=subprocess.DEVNULL,timeout=900)
    verify=run([signer,"verify","--verbose","--print-certs",part],env=env,capture_output=True,text=True,timeout=240).stdout
    actual=re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)",verify)
    assert actual and actual.group(1).lower().replace(":","")==CERT,"Owner signing certificate mismatch"
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in verify
    part.replace(signed)
    log("NEXUS_SIGNED",json.dumps({"sha256":sha(signed),"bytes":signed.stat().st_size,"cert_sha256":CERT}))
    return signed

def transfer(signed):
    sys.path.insert(0,str(CONTROL))
    from asterion_remote.native_transfer import TransferRequest,start_transfer,transfer_status
    expected_sha=sha(signed)
    size=signed.stat().st_size
    def authorize(path):
        actual=Path(path).resolve(strict=True)
        assert actual==signed.resolve(strict=True) and actual.stat().st_size==size and sha(actual)==expected_sha
        return actual
    job=start_transfer(TransferRequest(pc_path=str(signed),name=signed.name),authorize)
    assert job.get("id"),"Native transfer job not accepted"
    log("NEXUS_TRANSFER_ID",job["id"])
    deadline=time.monotonic()+3600
    while time.monotonic()<deadline:
        state=transfer_status(job["id"])
        if state.get("state") in ("completed","failed","cancelled"):
            receipt=state.get("result") or {}
            assert state["state"]=="completed" and receipt.get("verified") is True and receipt.get("sha256")==expected_sha and receipt.get("size")==size,"Phone transfer verification failed"
            return {"source":HEAD,"package":"com.asterion.nexus","sha256":expected_sha,"bytes":size,
                    "cert_sha256":CERT,"transfer_id":job["id"],"phone_verified":True,"installed":False}
        time.sleep(5)
    raise TimeoutError("Native transfer deadline reached; no verified receipt")

def main():
    assert os.name=="nt"
    ROOT.mkdir(parents=True,exist_ok=True)
    authorization=auth()
    run_id,gates=gate(authorization)
    unsigned=obtain(authorization,run_id)
    signed=sign(unsigned)
    receipt=transfer(signed)
    receipt["gates"]=gates
    (ROOT/"verified-phone-transfer.json").write_text(json.dumps(receipt,indent=2),encoding="utf-8")
    log("NEXUS_PHONE_DELIVERY_VERIFIED",json.dumps(receipt))
    # Retain signed APK and receipt; remove only our verified staging copies.
    unsigned.unlink(missing_ok=True)
    (ROOT/"candidate.zip").unlink(missing_ok=True)

if __name__=="__main__":
    main()
