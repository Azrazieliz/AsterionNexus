#!/usr/bin/env python3
"""Build a real, revision-pinned PP-OCRv5 model package for the inherited runtime.
Public model assets only. Fail closed on artifact, graph, or inference mismatch.
"""
import hashlib
import json
import pathlib
import tempfile
import zipfile

import numpy as np
import onnx
import onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont
from huggingface_hub import HfApi, hf_hub_download

ROOT = pathlib.Path(__file__).resolve().parents[1]
REPO = "ogkalu/ppocr-v5-onnx"
REV = "3b76726c3606c4af92f5133b359ed14e0d30a455"
FILES = {
    "det.onnx": "ch_PP-OCRv5_mobile_det.onnx",
    "rec.onnx": "ch_PP-OCRv5_rec_mobile_infer.onnx",
    "dict.txt": "ppocrv5_dict.txt",
}
FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"

def digest(path, algo="sha256"):
    with open(path, "rb") as f:
        return hashlib.file_digest(f, algo).hexdigest()

def verified_download(name, meta):
    p = pathlib.Path(hf_hub_download(REPO, name, revision=REV))
    if p.stat().st_size != meta.size:
        raise RuntimeError("Artifact size mismatch: " + name)
    if meta.lfs:
        assert digest(p) == meta.lfs.sha256, name
    else:
        raw = p.read_bytes()
        assert hashlib.sha1(f"blob {len(raw)}\0".encode()+raw).hexdigest() == meta.blob_id, name
    return p

def normalize_graph(src, dst):
    model = onnx.load(str(src))
    graph = model.graph
    assert len(graph.input) == 1 and len(graph.output) == 1
    # Names are an adapter contract, never a change to model computation.
    old = graph.input[0].name
    if old != "x":
        assert "x" not in {v for n in graph.node for v in list(n.input)+list(n.output)}
        graph.input[0].name = "x"
        for n in graph.node:
            for i, v in enumerate(n.input):
                if v == old:
                    n.input[i] = "x"
    old = graph.output[0].name
    if old != "fetch_name_0":
        assert "fetch_name_0" not in {v for n in graph.node for v in n.output}
        graph.node.append(onnx.helper.make_node("Identity",[old],["fetch_name_0"]))
        graph.output[0].name = "fetch_name_0"
    onnx.checker.check_model(model)
    onnx.save(model, str(dst))

def tensor(image, mean, scale):
    x = np.asarray(image.convert("RGB"), dtype=np.float32)[:,:,::-1] / 255.0
    return np.ascontiguousarray(((x-np.asarray(mean,dtype=np.float32))/np.asarray(scale,dtype=np.float32)).transpose(2,0,1)[None])

def recognize(rec, image, labels):
    w = min(320, max(1, round(image.width*48/image.height)))
    resized = image.resize((w,48), Image.Resampling.BILINEAR)
    arr = np.zeros((1,3,48,320),dtype=np.float32)
    arr[:,:,:,:w] = tensor(resized,[.5]*3,[.5]*3)
    y = rec.run(None,{"x":arr})[0]
    assert y.ndim==3 and y.shape[-1]==len(labels)
    result=[]
    prev=0
    for i in np.argmax(y[0], axis=-1):
        if i and i!=prev:
            result.append(labels[i])
        prev=i
    return "".join(result)

def main():
    info=HfApi().model_info(REPO,revision=REV,files_metadata=True)
    assert info.sha==REV
    meta={x.rfilename:x for x in info.siblings}
    with tempfile.TemporaryDirectory() as work:
        work=pathlib.Path(work)
        source_hashes={}
        for local, remote in FILES.items():
            src=verified_download(remote,meta[remote])
            source_hashes[remote]=digest(src)
            if local.endswith(".onnx"):
                normalize_graph(src,work/local)
            else:
                (work/local).write_bytes(src.read_bytes())
        chars=(work/"dict.txt").read_text(encoding="utf-8-sig").splitlines()
        assert len(chars)==18383, len(chars)
        labels=[""]+chars+[" "]
        opts=ort.SessionOptions()
        opts.intra_op_num_threads=2
        det=ort.InferenceSession(str(work/"det.onnx"),sess_options=opts,providers=["CPUExecutionProvider"])
        rec=ort.InferenceSession(str(work/"rec.onnx"),sess_options=opts,providers=["CPUExecutionProvider"])
        evidence=[]
        for expected,index in [("今日は雨です",0),("今天下雨",2),("學習中文",3)]:
            font=ImageFont.truetype(FONT,40,index=index)
            im=Image.new("RGB",(320,96),"white")
            ImageDraw.Draw(im).text((12,14),expected,font=font,fill="black")
            d=det.run(None,{"x":tensor(im,[.485,.456,.406],[.229,.224,.225])})[0]
            assert d.ndim==4 and d.shape[1]==1 and np.isfinite(d).all()
            ys,xs=np.where(d[0,0]>.3)
            assert len(xs)>0,"Detector found no text"
            h,w=d.shape[-2:]
            box=(max(0,int(xs.min()*320/w)-8),max(0,int(ys.min()*96/h)-8),
                 min(320,int((xs.max()+1)*320/w)+8),min(96,int((ys.max()+1)*96/h)+8))
            actual=recognize(rec,im.crop(box),labels)
            print(expected,repr(actual),flush=True)
            assert actual.replace(" ","")==expected, (expected,actual)
            evidence.append({"expected":expected,"actual":actual,"detected_bounds":box})
        metadata={"model_id":"asterion-ppocrv5-cjk","model_file":"det.onnx",
                  "task":"ocr","supported_tasks":["ocr"],"runtime":"onnx",
                  "source_repository":REPO,"source_revision":REV,
                  "source_sha256":source_hashes,"license":"Apache-2.0",
                  "reference_smoke":evidence,"device_certified":False}
        (work/"model.json").write_text(json.dumps(metadata,ensure_ascii=False,indent=2))
        dest=ROOT/"fusion/src/main/assets/bundled_ppocrv5_cjk.zip"
        dest.parent.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(dest,"w",compression=zipfile.ZIP_DEFLATED) as z:
            for f in ("det.onnx","rec.onnx","dict.txt","model.json"):
                z.write(work/f,f)
        (ROOT/"nexus_ocr_smoke.json").write_text(json.dumps({**metadata,"bundle_sha256":digest(dest)},ensure_ascii=False,indent=2))
        print("Verified candidate",dest)
if __name__=="__main__":
    main()
