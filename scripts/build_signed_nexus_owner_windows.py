#!/usr/bin/env python3
"""Build a single source-pinned, signed, co-installable Nexus APK on owner Windows.

Asterion Control's established authenticated PC worker may invoke this script.
Never requires or prints cloud credentials, never alters other Asterion apps,
never signs Nexus with Downloader/Core/Rounder certificates.
"""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time

REPO="https://github.com/Azrazieliz/AsterionNexus.git"
DONOR="https://github.com/Azrazieliz/AsterionDownloader.git"
BRANCH=os.environ.get("ASTERION_NEXUS_BRANCH","work/merge-core-downloader-rounder")
if BRANCH not in {"work/merge-core-downloader-rounder",
                  "work/nexus-saf-sources-20261126",
                  "work/nexus-harmonization-20261126"}:
    raise RuntimeError("Unapproved Nexus build branch")
DONOR_BRANCH="repair/downloader-global-search-20261121"
DONOR_COMMIT="71f38b91af637bbb0aed74906eea2a92fbb17d03"
WHEEL="yt_dlp-2026.10.26-py3-none-any.whl"
WHEEL_SHA="a05c5b5d8562f7e8a62f5334348b406af4930d56d3a65f8021d14af0e22339ae"
BASE=Path("D:/AsterionNexusBuild")
ROOT=Path(os.environ.get("ASTERION_NEXUS_SOURCE", str(BASE/"source")))
BUILD_HOME=BASE/"toolchain"
EXPECTED_SHA=os.environ.get("ASTERION_NEXUS_SHA","")
DONOR_DIR=Path("D:/AsterionNexusBuild/downloader-source")
OUTPUT=Path("D:/AsterionNexusBuild/output")
OWNER=Path(os.environ["LOCALAPPDATA"])/"AsterionNexus"/"signing"
SOURCE_SDK=Path(os.environ.get("ANDROID_HOME",str(Path.home()/"AppData/Local/Android/Sdk")))
SOURCE_JAVA=Path("C:/Program Files/Android/Android Studio1/jbr")
SOURCE_GRADLE=Path.home()/".gradle/wrapper/dists/gradle-8.13-bin"
SDK=BUILD_HOME/"android-sdk"
JAVA=BUILD_HOME/"jbr"
GRADLE_HOME=BUILD_HOME/"gradle-home"
GRADLE_DIST=BUILD_HOME/"gradle-8.13"
TEMP=BUILD_HOME/"tmp"

def mirror(source,target):
    if not source.is_dir():return
    target.mkdir(parents=True,exist_ok=True)
    # No /MIR, /PURGE, or moves. Other projects retain their installed tools.
    result=subprocess.run(["robocopy",str(source),str(target),"/E","/XJ",
        "/R:1","/W:1","/NFL","/NDL","/NJH","/NJS","/NP"],
        stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=1800)
    if result.returncode>=8:
        raise RuntimeError("Toolchain copy failed: "+str(source)+" -> "+str(target))
    
def prepare_d_toolchain():
    for path in (ROOT,OUTPUT,BUILD_HOME,SDK,JAVA,GRADLE_HOME,GRADLE_DIST,TEMP):
        if path.resolve().drive.upper()!="D:":
            raise RuntimeError("Nexus workspace or toolchain must be on D: "+str(path))
    BUILD_HOME.mkdir(parents=True,exist_ok=True)
    TEMP.mkdir(parents=True,exist_ok=True)
    GRADLE_HOME.mkdir(parents=True,exist_ok=True)
    if not (JAVA/"bin/java.exe").is_file():mirror(SOURCE_JAVA,JAVA)
    if not (GRADLE_DIST/"bin/gradle.bat").is_file():
        choices=tuple(SOURCE_GRADLE.glob("*/gradle-8.13"))
        if not choices:raise RuntimeError("Verified Gradle distribution missing")
        mirror(choices[0],GRADLE_DIST)
    for name in ("platforms/android-36","platform-tools","build-tools",
                 "cmdline-tools","licenses","ndk/26.3.11579264","cmake"):
        if SOURCE_SDK.resolve()!=SDK.resolve():mirror(SOURCE_SDK/name,SDK/name)
    # Gradle dependency resolution, Android preferences, SDK installs and JVM
    # temp files must not silently expand the nearly full system drive.
    android_home=BUILD_HOME/"android-user"
    android_home.mkdir(parents=True,exist_ok=True)
    env=os.environ.copy()
    env.update({"JAVA_HOME":str(JAVA),"ANDROID_HOME":str(SDK),
        "ANDROID_SDK_ROOT":str(SDK),"ANDROID_USER_HOME":str(android_home),
        "GRADLE_USER_HOME":str(GRADLE_HOME),"TEMP":str(TEMP),"TMP":str(TEMP)})
    env["JAVA_TOOL_OPTIONS"]=(env.get("JAVA_TOOL_OPTIONS","")+" -Djava.io.tmpdir="+str(TEMP)).strip()
    os.environ.update({k:env[k] for k in ("TEMP","TMP","GRADLE_USER_HOME","ANDROID_USER_HOME","ANDROID_HOME","ANDROID_SDK_ROOT","JAVA_HOME","JAVA_TOOL_OPTIONS")})
    print("NEXUS_BUILD_TOOLCHAIN="+str(BUILD_HOME),flush=True)
    return env

def cmd(*args, cwd=None, env=None, capture=True,timeout=2400):
    p=subprocess.run([str(x) for x in args],cwd=cwd,env=env,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        timeout=timeout,check=False)
    if p.returncode:
        trail=(p.stdout or b"")[-7000:].decode("utf-8","replace")
        print(trail,flush=True)
        raise RuntimeError(f"Command failed {args[0]} ({p.returncode})")
    return (p.stdout or b"").decode("utf-8","replace").strip()

def ensure_worktree():
    if not (ROOT/".git").exists():
        if ROOT.exists():
            raise RuntimeError("Nexus workspace exists but is not a Git checkout; do not overwrite")
        ROOT.parent.mkdir(parents=True,exist_ok=True)
        cmd("git","clone","--single-branch","--branch",BRANCH,REPO,ROOT,timeout=900)
    if cmd("git","status","--porcelain",cwd=ROOT):
        raise RuntimeError("Nexus workspace has uncommitted changes; preserve for owner review")
    cmd("git","fetch","origin",BRANCH,cwd=ROOT)
    sha=cmd("git","rev-parse","FETCH_HEAD",cwd=ROOT)
    if EXPECTED_SHA and sha!=EXPECTED_SHA:
        raise RuntimeError("Nexus branch changed; refuse to build a different source revision")
    cmd("git","checkout","--detach",sha,cwd=ROOT)
    return sha

def restore_wheel():
    if not (DONOR_DIR/".git").is_dir():
        if DONOR_DIR.exists():
            raise RuntimeError("Source donor workspace occupied; refusing overwrite")
        cmd("git","clone","--filter=blob:none","--single-branch","--branch",
            DONOR_BRANCH,DONOR,DONOR_DIR,timeout=900)
    donor_sha=cmd("git","rev-parse","HEAD",cwd=DONOR_DIR)
    if donor_sha!=DONOR_COMMIT:
        raise RuntimeError("Downloader donor SHA drift; do not substitute another extractor")
    dest=ROOT/"app/src/main/assets"/WHEEL
    dest.parent.mkdir(parents=True,exist_ok=True)
    with dest.open("wb") as stream:
        process=subprocess.Popen(["git","show",f"{DONOR_COMMIT}:app/src/main/assets/{WHEEL}"],
                                stdout=stream,stderr=subprocess.PIPE,cwd=DONOR_DIR)
        _,stderr=process.communicate(timeout=180)
        if process.returncode:raise RuntimeError("Pinned extractor unavailable: "+stderr.decode(errors="replace")[:200])
    if hashlib.sha256(dest.read_bytes()).hexdigest()!=WHEEL_SHA:
        dest.unlink(missing_ok=True)
        raise RuntimeError("Downloader extractor SHA mismatch")
    print("PINNED_WHEEL_SHA256="+WHEEL_SHA,flush=True)

def owner_signer():
    OWNER.mkdir(parents=True,exist_ok=True)
    store=OWNER/"nexus-release.p12"
    secret=OWNER/"nexus-signing-password.txt"
    if store.exists()!=secret.exists():
        raise RuntimeError("Owner signing identity incomplete; do not rotate signer")
    if not store.exists():
        password=secrets.token_urlsafe(38)
        with secret.open("x",encoding="utf-8") as file:file.write(password)
        os.chmod(secret,0o600)
        # Restrict the signing area to the current Windows user, never public Git.
        username=os.environ.get("USERNAME","")
        if not username:raise RuntimeError("Owner user account missing")
        cmd("icacls",OWNER,"/inheritance:r","/grant:r",f"{username}:(OI)(CI)F")
        cmd(JAVA/"bin/keytool.exe","-genkeypair","-noprompt","-alias","nexus",
            "-keyalg","RSA","-keysize","3072","-sigalg","SHA256withRSA",
            "-validity","10950","-dname","CN=Asterion Nexus, O=Owner, C=FR",
            "-storetype","PKCS12","-keystore",store,
            "-storepass",password,"-keypass",password,timeout=180)
    else:
        password=secret.read_text(encoding="utf-8").strip()
    if len(password)<30:raise RuntimeError("Invalid owner's stable Nexus signing secret")
    return store,password

def main():
    if os.name!="nt":raise RuntimeError("Owner Windows signing pipeline only")
    env=prepare_d_toolchain()
    if not (GRADLE_DIST/"bin/gradle.bat").is_file():raise RuntimeError("Verified Gradle 8.13 distribution missing")
    if not (SDK/"platforms/android-36").is_dir():
        raise RuntimeError("Android SDK 36 not installed")
    if not (JAVA/"bin/java.exe").is_file():raise RuntimeError("Original Windows JDK missing")
    sha=ensure_worktree()
    restore_wheel()
    args=(GRADLE_DIST/"bin/gradle.bat","--gradle-user-home",GRADLE_HOME,"--no-daemon","--max-workers=2",
        ":app:testDebugUnitTest",":app:assembleRelease","--stacktrace")
    print("NEXUS_BUILD_COMMIT="+sha,flush=True)
    print("BUILD_TEST_AND_ASSEMBLE_START",flush=True)
    cmd(*args,cwd=ROOT,env=env,timeout=3600,capture=False)
    app=ROOT/"app/build/outputs/apk/release/app-release-unsigned.apk"
    if not app.is_file() or app.stat().st_size<10000:
        raise RuntimeError("Release unsigned artifact missing: build not certified")
    signer=next((x for x in (
        SDK/"build-tools/36.1.0/apksigner.bat",
        SDK/"build-tools/36.0.0/apksigner.bat") if x.exists()),None)
    aapt=next((x for x in (
        SDK/"build-tools/36.1.0/aapt.exe",
        SDK/"build-tools/36.0.0/aapt.exe") if x.exists()),None)
    if signer is None or aapt is None:raise RuntimeError("Android signer or package inspector unavailable")
    OUTPUT.mkdir(parents=True,exist_ok=True)
    destination=OUTPUT/f"AsterionNexus-{sha[:12]}.apk"
    if destination.exists():raise RuntimeError("Signed destination already exists; preserve prior artifact")
    key,password=owner_signer()
    env["NEXUS_OWNER_STORE_PASS"]=password
    cmd(signer,"sign","--ks",key,"--ks-type","PKCS12",
        "--ks-key-alias","nexus","--ks-pass","env:NEXUS_OWNER_STORE_PASS",
        "--key-pass","env:NEXUS_OWNER_STORE_PASS","--out",destination,app,env=env)
    verify=cmd(signer,"verify","--print-certs","--verbose",destination,env=env)
    package=cmd(aapt,"dump","badging",destination,env=env)
    if "package: name='com.asterion.nexus'" not in package:
        raise RuntimeError("Signed output package identity is not Nexus")
    fingerprint=re.search(r"certificate SHA-256 digest:\s*([0-9a-fA-F:]+)",verify)
    if fingerprint is None:raise RuntimeError("Release signer certificate not reported")
    receipt={
        "status":"SIGNED_LOCAL_NOT_PHONE_VERIFIED",
        "source_sha":sha,"donor_sha":DONOR_COMMIT,
        "package":"com.asterion.nexus",
        "apk_path":str(destination),
        "bytes":destination.stat().st_size,
        "sha256":hashlib.sha256(destination.read_bytes()).hexdigest(),
        "cert_sha256":fingerprint.group(1).lower(),
        "build":"passed","inherited_unit_tests":"passed",
        "installed":False,"owner_accepted":False}
    result=OUTPUT/f"Nexus-{sha[:12]}-receipt.json"
    result.write_text(json.dumps(receipt,sort_keys=True,indent=2),encoding="utf-8")
    print("NEXUS_OWNER_SIGNED_CANDIDATE="+json.dumps(receipt),flush=True)

if __name__=="__main__":
    try:main()
    except Exception as error:
        print("NEXUS_BUILD_FAILED="+type(error).__name__+":"+str(error)[:350],flush=True)
        raise
