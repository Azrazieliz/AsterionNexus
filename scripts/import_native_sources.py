#!/usr/bin/env python3
"""Pinned, non-destructive source import. No credentials, media or model weights."""
from pathlib import Path
import hashlib, json, re, shutil, subprocess, tempfile

ROOT=Path(__file__).resolve().parents[1]
DONORS={
"downloader":("Azrazieliz/AsterionDownloader","repair/downloader-global-search-20261121","71f38b91af637bbb0aed74906eea2a92fbb17d03"),
"core":("Azrazieliz/Asterion-Core","recovery/core-v2100-gaps-20261118","f453a9d5defd14dbb7fa9fcef107f99766bd65d1"),
"rounder":("Azrazieliz/AsterionRounder","feat/control-recursive-inventory-20261122","d6c9a1725a3fdc91d8e325625c25395b5e3962c4")}
CLASSES=("FgSearchIndex.java","FgTeraboxClient.java","FgTeraboxSearchIndex.java",
"FgRounderSources.java","FgRounderOrganizer.java","FgRounderOrganizeJob.java",
"ReaderLibrary.java","SeekableZip.java","BookArchive.java","RemoteBookSpool.java",
"StreamedText.java","FgRounderInventoryBridge.java")

def run(args,cwd=None):
 p=subprocess.run(args,cwd=cwd,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 if p.returncode:
  print(p.stdout[-7000:],flush=True)
  raise RuntimeError("Command failed: "+str(args[:5]))
 return p.stdout.strip()

def clone(name,temp):
 repo,branch,sha=DONORS[name]; dst=temp/name
 run(["git","clone","--quiet","--filter=blob:none","--sparse","--single-branch","--branch",branch,"https://github.com/"+repo+".git",str(dst)])
 paths={"downloader":["app/src","app/build.gradle.kts","build.gradle.kts","gradle.properties","settings.gradle.kts"],
        "core":["android_app/app/src","branding"],
        "rounder":["openlocaloffice-build",".github/workflows/openlocaloffice-build.yml"]}[name]
 run(["git","-C",str(dst),"sparse-checkout","set","--no-cone",*paths])
 actual=run(["git","-C",str(dst),"rev-parse","HEAD"])
 if actual!=sha: raise RuntimeError("Unexpected "+name+" head: "+actual)
 return dst

def cp(src,dst):
 if dst.exists(): raise RuntimeError("Refusing overwrite: "+str(dst))
 if not src.exists(): raise FileNotFoundError(src)
 dst.parent.mkdir(parents=True,exist_ok=True)
 if src.is_dir():
  shutil.copytree(src,dst,ignore=shutil.ignore_patterns("build",".gradle","*.keystore","*.jks","*.apk","*.aab","*.onnx","*.safetensors","__pycache__"))
 else: shutil.copy2(src,dst)

def write(path,content):
 dst=ROOT/path
 if dst.exists(): raise RuntimeError("Refusing overwrite "+str(dst))
 dst.parent.mkdir(parents=True,exist_ok=True)
 dst.write_text(content,encoding="utf8")

def assemble(rounder):
 import yaml
 steps=yaml.safe_load((rounder/".github/workflows/openlocaloffice-build.yml").read_text())["jobs"]["build"]["steps"]
 begin=next(i for i,s in enumerate(steps) if s.get("name")=="Reassemble source archive")
 end=next(i for i,s in enumerate(steps) if s.get("name")=="Export assembled source for implementation audit")
 skip={"Set up JDK 17","Install Android API 36","Set up Gradle 9.6","Stamp monotonic Android update identity"}
 for step in steps[begin:end]:
  if "run" not in step or step.get("name") in skip: continue
  cmd=step["run"]
  if "$"+"{{" in cmd: raise RuntimeError("Unresolved workflow expression: "+step.get("name",""))
  print("Rounder assembly:",step.get("name",""),flush=True)
  run(["bash","-euo","pipefail","-c",cmd],cwd=rounder/step.get("working-directory","."))
 project=rounder/"openlocaloffice-build/project"
 if not (project/"app/src/main/java/com/openlocal/office/MainActivity.java").is_file():
  raise RuntimeError("Rounder assembled source not found")
 return project

def main():
 if any((ROOT/x).exists() for x in ("app","fusion","rounder")):
  raise RuntimeError("Native tree exists; will not replace it")
 with tempfile.TemporaryDirectory(prefix="nexus-donor-") as name:
  temp=Path(name)
  down,core,rounder=(clone(k,temp) for k in ("downloader","core","rounder"))
  cp(down/"app/src",ROOT/"app/src")
  for path in ("app/build.gradle.kts","build.gradle.kts","gradle.properties","settings.gradle.kts"):
   cp(down/path,ROOT/path)
  (ROOT/"app/src/main/assets/yt_dlp-2026.10.26-py3-none-any.whl").unlink(missing_ok=True)
  gradle=ROOT/"app/build.gradle.kts"
  s=gradle.read_text().replace('applicationId = "com.azrael.pixivdumpsync"','applicationId = "com.asterion.nexus"')
  if s.count('applicationId = "com.asterion.nexus"')!=1: raise RuntimeError("Package identity was not isolated")
  s=s.replace('    implementation("org.jsoup:', '    implementation(project(":fusion"))\n    implementation(project(":rounder"))\n    implementation("org.jsoup:',1)
  gradle.write_text(s)
  manifest=ROOT/"app/src/main/AndroidManifest.xml"
  x=manifest.read_text()
  x=x.replace('android:name="com.azrael.pixivdumpsync.permission.REQUEST_ASTERION_DOWNLOAD"','android:name="com.asterion.nexus.permission.REQUEST_ASTERION_DOWNLOAD"')
  x=x.replace('android:name="com.azrael.pixivdumpsync.action.DOWNLOAD_REQUEST"','android:name="com.asterion.nexus.action.DOWNLOAD_REQUEST"')
  manifest.write_text(x)
  strings=ROOT/"app/src/main/res/values/strings.xml"
  strings.write_text(re.sub(r'(<string name="app_name">).*?(</string>)',r'\1Asterion Nexus\2',strings.read_text()))
  st=ROOT/"settings.gradle.kts"
  st.write_text(st.read_text().replace('rootProject.name = "PixivDumpSync"','rootProject.name = "AsterionNexus"')+'\ninclude(":fusion", ":rounder")\n')
  b=ROOT/"build.gradle.kts"
  b.write_text(b.read_text().replace('\n}','\n    id("com.android.library") version "8.7.3" apply false\n    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false\n}',1))
  p=ROOT/"gradle.properties"
  p.write_text(p.read_text().replace("android.useAndroidX=false","android.useAndroidX=true"))
  cp(core/"android_app/app/src/main/java",ROOT/"fusion/src/main/java")
  cp(core/"android_app/app/src/main/res",ROOT/"fusion/src/main/res")
  cp(core/"android_app/app/src/main/assets",ROOT/"fusion/src/main/assets")
  for origin,target in (("AsterionCore-Logo.png","drawable-nodpi/asterioncore_logo.png"),("AsterionCore-Splash.png","drawable-nodpi/asterioncore_splash.png"),("AsterionCore-Icon-512.png","drawable-nodpi/asterioncore_icon.png"),("AsterionCore-Icon-512.png","mipmap-nodpi/ic_launcher.png"),("AsterionCore-Icon-512.png","mipmap-nodpi/ic_launcher_round.png")):
   cp(core/"branding"/origin,ROOT/"fusion/src/main/res"/target)
  write("fusion/src/main/AndroidManifest.xml",'<?xml version="1.0" encoding="utf-8"?><manifest xmlns:android="http://schemas.android.com/apk/res/android" />\n')
  write("fusion/build.gradle.kts",'''plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "com.ailm.android"
 compileSdk = 35
 defaultConfig { minSdk = 30 }
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 androidResources { noCompress += listOf("zip") }
}
dependencies {
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.core:core-splashscreen:1.0.1")
 implementation("androidx.activity:activity-compose:1.10.0")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
 implementation("androidx.navigation:navigation-compose:2.8.5")
 implementation("androidx.work:work-runtime-ktx:2.10.0")
 implementation("androidx.datastore:datastore-preferences:1.2.1")
 implementation("androidx.graphics:graphics-path:1.1.0")
 implementation("androidx.paging:paging-runtime-ktx:3.3.6")
 implementation("androidx.room:room-runtime:2.6.1")
 implementation("androidx.room:room-ktx:2.6.1")
 implementation("androidx.documentfile:documentfile:1.0.1")
 implementation("io.coil-kt:coil-compose:2.7.0")
 implementation("io.coil-kt:coil-gif:2.7.0")
 implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
 implementation("org.tensorflow:tensorflow-lite:2.16.1")
 implementation("androidx.compose.ui:ui:1.7.6")
 implementation("androidx.compose.material3:material3:1.3.1")
 implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
}
''')
  assembled=assemble(rounder)
  for cls in CLASSES:
   cp(assembled/"app/src/main/java/com/openlocal/office/core"/cls,ROOT/"rounder/src/main/java/com/openlocal/office/core"/cls)
  write("rounder/src/main/AndroidManifest.xml",'<?xml version="1.0" encoding="utf-8"?><manifest xmlns:android="http://schemas.android.com/apk/res/android" />\n')
  write("rounder/build.gradle.kts",'''plugins { id("com.android.library") }
android {
 namespace = "com.openlocal.office"
 compileSdk = 35
 defaultConfig { minSdk = 29 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { testImplementation("junit:junit:4.13.2") }
''')
  files=[]
  for key,folder in (("downloader","app/src"),("core","fusion/src/main"),("rounder","rounder/src/main")):
   for path in sorted((ROOT/folder).rglob("*")):
    if path.is_file():
     files.append({"donor":key,"path":path.relative_to(ROOT).as_posix(),"sha256":hashlib.sha256(path.read_bytes()).hexdigest()})
  write("spec/source-import-manifest.v1.json",json.dumps({"version":"asterion.nexus.source-import.v1","donors":{k:{"repository":r,"branch":b,"sha":s} for k,(r,b,s) in DONORS.items()},"rounder":"pinned assembled workflow","files":files,"compile":"UNTESTED","phone":"UNTESTED"},indent=2)+"\n")
  print("Imported source files:",len(files),flush=True)

if __name__=="__main__": main()
