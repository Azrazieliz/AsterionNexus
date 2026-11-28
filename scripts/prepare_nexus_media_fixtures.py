#!/usr/bin/env python3
"""Generate original synthetic, playable media and textured/animated glTF for Android CI."""
import json
import pathlib
import struct
import subprocess
import zlib

root=pathlib.Path(__file__).resolve().parents[1]
out=root/"app/src/androidTest/assets/nexus-fixtures"
out.mkdir(parents=True,exist_ok=True)
for name,color,frequency in (("clip-a","red","440"),("clip-b","green","660")):
    subprocess.run(["ffmpeg","-v","error","-y","-f","lavfi","-i",f"color=c={color}:s=320x240:r=24:d=2",
        "-f","lavfi","-i",f"sine=frequency={frequency}:sample_rate=44100:duration=2",
        "-c:v","libx264","-pix_fmt","yuv420p","-g","24","-c:a","aac","-shortest","-movflags","+faststart",str(out/(name+".mp4"))],check=True)
def chunk(tag,body):
    return struct.pack(">I",len(body))+tag+body+struct.pack(">I",zlib.crc32(tag+body)&0xffffffff)
png=b"\x89PNG\r\n\x1a\n"+chunk(b"IHDR",struct.pack(">IIBBBBB",2,2,8,2,0,0,0))
png+=chunk(b"IDAT",zlib.compress(bytes([0,255,40,40,40,255,40,0,40,40,255,255,180,40])))+chunk(b"IEND",b"")
buf=bytearray()
views=[]
def view(data,target=None):
    while len(buf)%4:buf.append(0)
    item={"buffer":0,"byteOffset":len(buf),"byteLength":len(data)}
    if target:item["target"]=target
    index=len(views);views.append(item);buf.extend(data);return index
access=[]
def accessor(data,component,count,typ,target=None,limits=None):
    v=view(data,target);a={"bufferView":v,"componentType":component,"count":count,"type":typ}
    if limits:a["min"],a["max"]=limits
    index=len(access);access.append(a);return index
pos=accessor(struct.pack("<12f",-.7,-.7,0,.7,-.7,0,.7,.7,0,-.7,.7,0),5126,4,"VEC3",34962,([-.7,-.7,0],[.7,.7,0]))
normal=accessor(struct.pack("<12f",*( [0,0,1]*4)),5126,4,"VEC3",34962)
uv=accessor(struct.pack("<8f",0,0,1,0,1,1,0,1),5126,4,"VEC2",34962)
indices=accessor(struct.pack("<6H",0,1,2,0,2,3),5123,6,"SCALAR",34963)
times=accessor(struct.pack("<3f",0,1,2),5126,3,"SCALAR",limits=([0],[2]))
translations=accessor(struct.pack("<9f",0,0,0,.12,0,0,0,0,0),5126,3,"VEC3")
image=view(png)
while len(buf)%4:buf.append(0)
doc={"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
     "meshes":[{"primitives":[{"attributes":{"POSITION":pos,"NORMAL":normal,"TEXCOORD_0":uv},"indices":indices,"material":0}]}],
     "materials":[{"doubleSided":True,"pbrMetallicRoughness":{"baseColorTexture":{"index":0},"metallicFactor":0,"roughnessFactor":1}}],
     "textures":[{"source":0}],"images":[{"bufferView":image,"mimeType":"image/png"}],
     "animations":[{"name":"Fixture motion","samplers":[{"input":times,"output":translations,"interpolation":"LINEAR"}],"channels":[{"sampler":0,"target":{"node":0,"path":"translation"}}]}],
     "buffers":[{"byteLength":len(buf)}],"bufferViews":views,"accessors":access}
raw=json.dumps(doc,separators=(",",":")).encode()
raw+=b" "*((-len(raw))%4)
blob=struct.pack("<III",0x46546c67,2,12+8+len(raw)+8+len(buf))+struct.pack("<II",len(raw),0x4e4f534a)+raw+struct.pack("<II",len(buf),0x004e4942)+buf
(out/"textured-animation.glb").write_bytes(blob)
print("Created playable clips and textured animated GLB fixture")
