#!/usr/bin/env python3
"""Independent offline model candidate; no failed quality gate is waived."""
import json
import pathlib
import shutil
import tempfile
from huggingface_hub import HfApi, hf_hub_download
from transformers import M2M100Tokenizer
from prepare_nexus_translation import ROOT, ASSETS, verify_hub_file, hash_file, smoke, prepare_spm

REPO="Xenova/m2m100_418M"
REV="6540b5e5f5d78a277bc7de2b566362a61b772103"
ARTIFACTS={"encoder.onnx":"onnx/encoder_model_quantized.onnx",
           "decoder.onnx":"onnx/decoder_model_quantized.onnx",
           "sentencepiece.bpe.model":"sentencepiece.bpe.model",
           "vocab.json":"vocab.json","config.json":"config.json",
           "tokenizer_config.json":"tokenizer_config.json",
           "special_tokens_map.json":"special_tokens_map.json"}
PACKAGED=["encoder.onnx","decoder.onnx","source.spm","target.spm","vocab.json","config.json"]

def prepare():
    prepare_spm()
    info=HfApi().model_info(REPO,revision=REV,files_metadata=True)
    assert info.sha==REV
    meta={x.rfilename:x for x in info.siblings}
    ASSETS.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory() as temp:
        work=pathlib.Path(temp)
        for output,remote in ARTIFACTS.items():
            if remote not in meta and remote=="special_tokens_map.json":continue
            src=pathlib.Path(hf_hub_download(REPO,remote,revision=REV))
            verify_hub_file(src,meta[remote])
            shutil.copyfile(src,work/output)
        # Native tokenizer uses precisely the SentencePiece model and expanded
        # vocabulary of the upstream M2M100 tokenizer, with explicit language IDs.
        tokenizer=M2M100Tokenizer.from_pretrained(work,src_lang="zh",tgt_lang="en",local_files_only=True)
        config=json.loads((work/"config.json").read_text())
        config["nexus_source_prefix"]=[tokenizer.get_lang_id("zh")]
        config["nexus_target_prefix"]=[tokenizer.get_lang_id("en")]
        config["nexus_suppressed_ids"]=sorted(set(tokenizer.lang_code_to_id.values())|
            {tokenizer.bos_token_id,tokenizer.pad_token_id,tokenizer.unk_token_id})
        config["nexus_decoder_start"]=config["decoder_start_token_id"]
        config["nexus_pad"]=tokenizer.pad_token_id
        config["nexus_architecture"]="m2m100"
        assert config["model_type"]=="m2m_100"
        vocab=tokenizer.get_vocab()
        assert len(set(vocab.values()))==len(vocab)
        for text in ("今天下雨。","我正在看書。","我正在看书。"):
            ids=tokenizer.encode(text,add_special_tokens=True)
            pieces=tokenizer.sp_model.encode(text,out_type=str)
            assert ids==config["nexus_source_prefix"]+[vocab[p] for p in pieces]+[config["eos_token_id"]]
        (work/"vocab.json").write_text(json.dumps(vocab,ensure_ascii=False))
        (work/"config.json").write_text(json.dumps(config))
        for name in ("source.spm","target.spm"):shutil.copyfile(work/"sentencepiece.bpe.model",work/name)
        result=smoke(work,"zh")
        manifest={"language":"zh","revision":REV,"repo":REPO,"license":"MIT",
                  "reference_smoke_passed":True,"quality_certified":False,"decode_policy":"beam4-m2m100-v1","precision":"int8",
                  "sha256":{name:hash_file(work/name) for name in PACKAGED}}
        dest=ASSETS/"zh"
        if dest.exists():shutil.rmtree(dest)
        dest.mkdir()
        for name in PACKAGED:shutil.copyfile(work/name,dest/name)
        (dest/"manifest.json").write_text(json.dumps(manifest,indent=2))
        return {"manifest":manifest,"fixtures":result}
if __name__=="__main__":
    report=ROOT/"nexus_m2m100_smoke.json"
    try:
        report.write_text(json.dumps(prepare(),ensure_ascii=False,indent=2))
    except Exception as exc:
        report.write_text(json.dumps({"reference_smoke_passed":False,"error":str(exc)},ensure_ascii=False,indent=2))
        raise
