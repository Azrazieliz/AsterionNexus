#!/usr/bin/env python3
"""Prepare exact offline translation candidates; validate real graph/tokenizer inference.
Only public model files are downloaded at build time. Nothing uploads source books.
"""
import argparse
import hashlib
import json
import pathlib
import subprocess
import shutil
import tempfile

import numpy as np
import onnxruntime as ort
import sentencepiece as spm
from huggingface_hub import HfApi, hf_hub_download

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/translation-models"
SPM = ROOT / "app/src/main/cpp/third_party/sentencepiece"
SPM_REV = "17d7580d6407802f85855d2cc9190634e2c95624"
REVISIONS = {
    "ja": "1a906cfaaf7c8f4193f67f5885c082aa6dbd9d16",
    "ko": "d9fa1ac6008242100fecb4fff9b0a5917a1be81f",
    "zh": "39d480d52a9ea3065a1f117adfe4dbc55de10e6f",
}
FILES = {
    "encoder.onnx": "onnx/encoder_model_quantized.onnx",
    "decoder.onnx": "onnx/decoder_model_quantized.onnx",
    "source.spm": "source.spm",
    "target.spm": "target.spm",
    "vocab.json": "vocab.json",
    "config.json": "config.json",
}
FIXTURES = {
    "ja": [("今日は雨です。", ("rain",)), ("私は本を読みます。", ("book", "read"))],
    "ko": [("오늘은 비가 옵니다.", ("rain",)), ("나는 책을 읽습니다.", ("book", "read"))],
    "zh": [("今天下雨。", ("rain",)), ("我正在看書。", ("book", "read")), ("我正在看书。", ("book", "read"))],
}


def hash_file(path, algorithm="sha256"):
    h = hashlib.new(algorithm)
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_hub_file(path, metadata):
    if metadata.size != path.stat().st_size:
        raise RuntimeError(f"Hub size mismatch: {metadata.rfilename}")
    if metadata.lfs:
        if hash_file(path) != metadata.lfs.sha256:
            raise RuntimeError(f"LFS SHA-256 mismatch: {metadata.rfilename}")
    else:
        h = hashlib.sha1(f"blob {path.stat().st_size}\0".encode("ascii"))
        with path.open("rb") as f:
            for chunk in iter(lambda: f.read(1024 * 1024), b""):
                h.update(chunk)
        if h.hexdigest() != metadata.blob_id:
            raise RuntimeError(f"Git blob mismatch: {metadata.rfilename}")


def smoke(directory, language):
    vocabulary = json.loads((directory / "vocab.json").read_text())
    reverse = {value: key for key, value in vocabulary.items()}
    config = json.loads((directory / "config.json").read_text())
    eos, start, unknown = config["eos_token_id"], config.get("nexus_decoder_start",config["decoder_start_token_id"]), vocabulary["<unk>"]
    pad=config.get("nexus_pad",start)
    source_prefix=config.get("nexus_source_prefix",[])
    target_prefix=[start]+config.get("nexus_target_prefix",[])
    suppressed=set(config.get("nexus_suppressed_ids",[]))|{pad,unknown}
    src = spm.SentencePieceProcessor(model_file=str(directory / "source.spm"))
    tgt = spm.SentencePieceProcessor(model_file=str(directory / "target.spm"))
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 3
    opts.inter_op_num_threads = 1
    encoder = ort.InferenceSession(str(directory / "encoder.onnx"), sess_options=opts, providers=["CPUExecutionProvider"])
    decoder = ort.InferenceSession(str(directory / "decoder.onnx"), sess_options=opts, providers=["CPUExecutionProvider"])
    if {x.name for x in encoder.get_inputs()} != {"input_ids", "attention_mask"}:
        raise RuntimeError("Unexpected encoder interface")
    if {x.name for x in decoder.get_inputs()} != {"input_ids", "encoder_attention_mask", "encoder_hidden_states"}:
        raise RuntimeError("Unexpected decoder interface")
    results = []
    for text, expected in FIXTURES[language]:
        ids = [vocabulary.get(piece, unknown) for piece in src.encode(text, out_type=str)]
        if not ids or unknown in ids:
            raise RuntimeError("Unknown source tokens in reference fixture")
        input_ids = np.array([source_prefix + ids + [eos]], dtype=np.int64)
        mask = np.ones_like(input_ids)
        hidden, = encoder.run(["last_hidden_state"], {"input_ids": input_ids, "attention_mask": mask})
        # Rank by sequence likelihood, not fixture words.
        width = 4
        beams = [(target_prefix, 0.0)]
        completed = []
        for _ in range(max(48, min(384, input_ids.shape[1] * 4 + 32))):
            candidates = []
            for prefix, total in beams:
                scores, = decoder.run(["logits"], {
                    "input_ids": np.array([prefix], dtype=np.int64),
                    "encoder_attention_mask": mask,
                    "encoder_hidden_states": hidden,
                })
                logits = scores[0, -1].astype(np.float64)
                maximum = np.max(logits)
                normalizer = maximum + np.log(np.exp(logits - maximum).sum())
                for token in suppressed: logits[token] = -np.inf
                if len(prefix) == len(target_prefix):
                    logits[eos] = -np.inf
                top = np.argsort(-logits, kind="stable")[:width]
                for token in top:
                    if not np.isfinite(logits[token]):
                        continue
                    candidate = (prefix + [int(token)], total + float(logits[token] - normalizer))
                    if token == eos:
                        completed.append(candidate)
                    else:
                        candidates.append(candidate)
            rank = lambda pair: pair[1] / max(1,len(pair[0]) - len(target_prefix))
            completed = sorted(completed, key=rank, reverse=True)[:width]
            beams = sorted(candidates, key=lambda pair: pair[1], reverse=True)[:width]
            if not beams:
                break
            if len(completed) >= width and max(rank(pair) for pair in beams) <= rank(completed[-1]):
                break
        if not completed:
            raise RuntimeError("Reference inference did not complete")
        tokens = completed[0][0]
        result = tgt.decode([reverse[i] for i in tokens[len(target_prefix):-1]])
        print(f"{language}: {text!r} -> {result!r}", flush=True)
        if not result.strip() or result == text or not all(word in result.lower() for word in expected):
            raise RuntimeError(f"Reference semantic smoke failed for {language}: {result!r}")
        results.append({"input": text, "output": result, "source_ids": ids, "target_ids": tokens})
    return results


def prepare_language(language):
    revision = REVISIONS[language]
    repo = f"Xenova/opus-mt-{language}-en"
    info = HfApi().model_info(repo, revision=revision, files_metadata=True)
    if info.sha != revision:
        raise RuntimeError("Unexpected Hub revision")
    metadata = {x.rfilename: x for x in info.siblings}
    # Korean and Chinese quantized candidates failed the unchanged fidelity gate.
    # Evaluate full precision graphs, not lower the test standard.
    artifacts = dict(FILES)
    if language in ("ko", "zh"):
        artifacts.update({"encoder.onnx": "onnx/encoder_model.onnx", "decoder.onnx": "onnx/decoder_model.onnx"})
    ASSETS.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=ASSETS) as work:
        work = pathlib.Path(work)
        for output, name in artifacts.items():
            source = pathlib.Path(hf_hub_download(repo, name, revision=revision))
            verify_hub_file(source, metadata[name])
            shutil.copyfile(source, work / output)
        results = smoke(work, language)
        manifest = {"language": language, "revision": revision, "repo": repo,
                    "reference_smoke_passed": True, "quality_certified": False, "decode_policy": "beam4-v1", "precision": "fp32" if language in ("ko", "zh") else "int8",
                    "sha256": {name: hash_file(work / name) for name in FILES}}
        (work / "manifest.json").write_text(json.dumps(manifest, indent=2))
        dest = ASSETS / language
        if dest.exists():
            shutil.rmtree(dest)
        shutil.copytree(work, dest)
    return {"manifest": manifest, "fixtures": results}


def prepare_spm():
    if not (SPM / ".git").exists():
        SPM.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "init", str(SPM)], check=True)
        subprocess.run(["git", "-C", str(SPM), "remote", "add", "origin",
                        "https://github.com/google/sentencepiece.git"], check=True)
    subprocess.run(["git", "-C", str(SPM), "fetch", "--depth=1", "origin", SPM_REV], check=True)
    subprocess.run(["git", "-C", str(SPM), "checkout", "--detach", SPM_REV], check=True)
    result = subprocess.check_output(["git", "-C", str(SPM), "rev-parse", "HEAD"], text=True).strip()
    if result != SPM_REV:
        raise RuntimeError("Unexpected SentencePiece revision")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--languages", nargs="+", choices=list(REVISIONS), default=["ja", "ko"])
    args = parser.parse_args()
    prepare_spm()
    evidence = {}
    failed = []
    for language in args.languages:
        try:
            evidence[language] = prepare_language(language)
        except Exception as exc:
            evidence[language] = {"error": str(exc), "reference_smoke_passed": False}
            failed.append(language)
        finally:
            (ROOT / "nexus_translation_smoke.json").write_text(json.dumps(evidence, indent=2, ensure_ascii=False))
    if failed:
        raise SystemExit("Unvalidated translation languages: " + ", ".join(failed))
