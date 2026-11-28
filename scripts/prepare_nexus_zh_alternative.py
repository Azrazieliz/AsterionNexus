#!/usr/bin/env python3
"""Alternative candidate only: do not promote a failed model or weaken fixtures.
Export a different independently trained Marian model to the existing native interface.
"""
import json
import pathlib
import tempfile
import shutil
import numpy as np
import torch
import onnx
from huggingface_hub import HfApi, hf_hub_download
from transformers import MarianMTModel
from prepare_nexus_translation import ROOT, ASSETS, FILES, verify_hub_file, hash_file, smoke, prepare_spm

REPO = "HPLT/translate-zh_hant-en-v1.0-hplt_opus"
REV = "475805da2af9e02942b009d255ea7d2362e0b144"
REQUIRED = ["config.json","vocab.json","source.spm","target.spm","pytorch_model.bin"]

class Encoder(torch.nn.Module):
    def __init__(self,model):
        super().__init__()
        self.encoder=model.model.encoder
    def forward(self,input_ids,attention_mask):
        return self.encoder(input_ids=input_ids,attention_mask=attention_mask,return_dict=False)[0]

class Decoder(torch.nn.Module):
    def __init__(self,model):
        super().__init__()
        self.decoder=model.model.decoder
        self.lm_head=model.lm_head
        self.register_buffer("final_logits_bias",model.final_logits_bias)
    def forward(self,input_ids,encoder_attention_mask,encoder_hidden_states):
        hidden=self.decoder(input_ids=input_ids,encoder_hidden_states=encoder_hidden_states,
                            encoder_attention_mask=encoder_attention_mask,use_cache=False,return_dict=False)[0]
        return self.lm_head(hidden)+self.final_logits_bias

def prepare():
    prepare_spm()
    info=HfApi().model_info(REPO,revision=REV,files_metadata=True)
    assert info.sha==REV
    meta={x.rfilename:x for x in info.siblings}
    torch.set_num_threads(3)
    ASSETS.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory() as temp:
        work=pathlib.Path(temp)
        for name in REQUIRED:
            src=pathlib.Path(hf_hub_download(REPO,name,revision=REV))
            verify_hub_file(src,meta[name]);shutil.copyfile(src,work/name)
        # Explicit weights-only load; no repository Python or custom code.
        model=MarianMTModel.from_pretrained(work,local_files_only=True,
            trust_remote_code=False,weights_only=True).float().eval()
        enc=Encoder(model).eval();dec=Decoder(model).eval()
        source=torch.tensor([[3,4,5,0]],dtype=torch.long)
        mask=torch.ones_like(source)
        prefix=torch.tensor([[model.config.decoder_start_token_id,3]],dtype=torch.long)
        with torch.no_grad():
            hidden=enc(source,mask)
            torch.onnx.export(enc,(source,mask),str(work/"encoder.onnx"),
                input_names=["input_ids","attention_mask"],output_names=["last_hidden_state"],
                dynamic_axes={"input_ids":{0:"batch",1:"source"},"attention_mask":{0:"batch",1:"source"},"last_hidden_state":{0:"batch",1:"source"}},
                opset_version=17,dynamo=False)
            torch.onnx.export(dec,(prefix,mask,hidden),str(work/"decoder.onnx"),
                input_names=["input_ids","encoder_attention_mask","encoder_hidden_states"],output_names=["logits"],
                dynamic_axes={"input_ids":{0:"batch",1:"target"},"encoder_attention_mask":{0:"batch",1:"source"},
                  "encoder_hidden_states":{0:"batch",1:"source"},"logits":{0:"batch",1:"target"}},
                opset_version=17,dynamo=False)
        import onnxruntime as ort
        for name in ("encoder.onnx","decoder.onnx"):onnx.checker.check_model(str(work/name))
        # Verify export parity independently of the language smoke test.
        opts=ort.SessionOptions();opts.intra_op_num_threads=2
        e=ort.InferenceSession(str(work/"encoder.onnx"),sess_options=opts,providers=["CPUExecutionProvider"])
        d=ort.InferenceSession(str(work/"decoder.onnx"),sess_options=opts,providers=["CPUExecutionProvider"])
        with torch.no_grad():
            h=e.run(None,{"input_ids":source.numpy(),"attention_mask":mask.numpy()})[0]
            y=d.run(None,{"input_ids":prefix.numpy(),"encoder_attention_mask":mask.numpy(),"encoder_hidden_states":h})[0]
            np.testing.assert_allclose(h,hidden.numpy(),rtol=3e-4,atol=3e-4)
            np.testing.assert_allclose(y,dec(prefix,mask,hidden).numpy(),rtol=5e-4,atol=5e-4)
        del e,d,model,enc,dec
        results=smoke(work,"zh")
        manifest={"language":"zh","revision":REV,"repo":REPO,"license":"CC-BY-4.0",
                  "reference_smoke_passed":True,"quality_certified":False,"decode_policy":"beam4-v1","precision":"fp32",
                  "sha256":{name:hash_file(work/name) for name in FILES}}
        dest=ASSETS/"zh"
        if dest.exists():shutil.rmtree(dest)
        dest.mkdir()
        for name in FILES:shutil.copyfile(work/name,dest/name)
        (dest/"manifest.json").write_text(json.dumps(manifest,indent=2))
        return {"manifest":manifest,"fixtures":results}

if __name__=="__main__":
    report=ROOT/"nexus_zh_alternative_smoke.json"
    try:
        evidence=prepare()
        report.write_text(json.dumps(evidence,ensure_ascii=False,indent=2))
    except Exception as exc:
        report.write_text(json.dumps({"reference_smoke_passed":False,"error":str(exc)},ensure_ascii=False,indent=2))
        raise
