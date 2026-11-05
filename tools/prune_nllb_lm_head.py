"""NLLB decoder'inin cikti katmanini (lm_head) yalniz Latin alfabeli tokenlara indirir.

Neden: Orijinal model her adimda 256.206 tokenin hepsini hesapliyor; bunun icin
256206x1024'luk agirligi her adimda transpoze edip float'a ceviriyor. Turkce,
Endonezce ve Ingilizce cikti yalniz Latin harfli tokenlardan olusur. Ayni uint8
agirliklarin bu alt kumesi, ayni olcek/sifir noktasi ile tamsayi carpimina
baglanir; dolayisiyla secilen tokenlarin puanlari degismez.

Cikti:
  <cikti_model>          : budanmis decoder
  nllb_logit_map.txt     : her satir bir token id (cikti sutun sirasi)

Kullanim: python prune_nllb_lm_head.py <decoder.onnx> <nllb_vocab.txt> <cikti_model> <cikti_klasoru>
"""
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto

ALLOWED = [(0x20, 0x7E), (0xA0, 0x24F), (0x2010, 0x2027), (0x20AC, 0x20AC), (0x20BA, 0x20BA)]
EOS = 2


def allowed_char(c: str) -> bool:
    o = ord(c)
    return any(a <= o <= b for a, b in ALLOWED)


def main() -> None:
    src, vocab_path, dst, out_dir = sys.argv[1:5]
    vocab = Path(vocab_path).read_text(encoding="utf-8").split("\n")[:-1]

    keep = [EOS]
    for i, piece in enumerate(vocab):
        if i < 4:
            continue  # <s> <pad> </s> <unk>
        if len(piece) == 8 and piece[3] == "_":
            continue  # dil kodlari (tur_Latn vb.)
        if piece.startswith("<") and piece.endswith(">"):
            continue
        body = piece.replace("\u2581", "")
        if all(allowed_char(c) for c in body):
            keep.append(i)
    keep = np.array(keep, dtype=np.int64)

    m = onnx.load(src)
    g = m.graph
    inits = {t.name: t for t in g.initializer}
    w_q = numpy_helper.to_array(inits["model.shared.weight_merged_0_quantized"])  # [V,1024] uint8
    if w_q.dtype != np.uint8:
        raise SystemExit(f"Beklenmeyen agirlik tipi: {w_q.dtype}")
    sub = np.ascontiguousarray(w_q[keep].T)  # [1024, K]
    g.initializer.append(numpy_helper.from_array(sub, "lm_head_sub_q"))
    # MatMulInteger icin sifir noktasi skaler degil 1-D veya skaler olabilir; ayni skaleri kullan.
    w_scale = "model.shared.weight_merged_0_scale"
    w_zp = "model.shared.weight_merged_0_zero_point"

    ifn = [n for n in g.node if n.op_type == "If"]
    if len(ifn) != 1:
        raise SystemExit("Tek If dugumu bekleniyordu")
    replaced = 0
    for attr in ifn[0].attribute:
        b = attr.g
        nodes = list(b.node)
        mm = [n for n in nodes if n.name == "/lm_head/MatMul"]
        if len(mm) != 1:
            raise SystemExit(f"{attr.name}: lm_head bulunamadi")
        mm = mm[0]
        hidden = mm.input[0]
        # eski transpoze + dequantize zincirini kaldir
        dq = [n for n in nodes if n.output[0] == mm.input[1]]
        if len(dq) != 1 or dq[0].op_type != "DequantizeLinear":
            raise SystemExit(f"{attr.name}: beklenmeyen agirlik zinciri")
        tr = [n for n in nodes if n.output[0] == dq[0].input[0]]
        if len(tr) != 1 or tr[0].op_type != "Transpose":
            raise SystemExit(f"{attr.name}: beklenmeyen transpoze")
        pre = attr.name
        new_nodes = [
            helper.make_node("DynamicQuantizeLinear", [hidden], [f"{pre}_lmq", f"{pre}_lms", f"{pre}_lmz"], name=f"{pre}_lm_dql"),
            helper.make_node("MatMulInteger", [f"{pre}_lmq", "lm_head_sub_q", f"{pre}_lmz", w_zp], [f"{pre}_lmi"], name=f"{pre}_lm_mmi"),
            helper.make_node("Cast", [f"{pre}_lmi"], [f"{pre}_lmf"], to=TensorProto.FLOAT, name=f"{pre}_lm_cast"),
            helper.make_node("Mul", [f"{pre}_lms", w_scale], [f"{pre}_lmsc"], name=f"{pre}_lm_scale"),
            helper.make_node("Mul", [f"{pre}_lmf", f"{pre}_lmsc"], [mm.output[0]], name=f"{pre}_lm_out"),
        ]
        out = []
        for n in nodes:
            if n is mm:
                out.extend(new_nodes)
            elif n is dq[0] or n is tr[0]:
                continue
            else:
                out.append(n)
        del b.node[:]
        b.node.extend(out)
        for o in b.output:
            if o.name == "logits":
                o.type.tensor_type.shape.dim[-1].dim_value = len(keep)
        replaced += 1
    for o in g.output:
        if o.name == "logits":
            o.type.tensor_type.shape.dim[-1].dim_value = len(keep)

    onnx.save(m, dst)
    Path(out_dir).mkdir(parents=True, exist_ok=True)
    with open(Path(out_dir) / "nllb_logit_map.txt", "w", encoding="utf-8", newline="\n") as f:
        for i in keep:
            f.write(f"{int(i)}\n")
    print(f"branches={replaced} kept={len(keep)} of {len(vocab)}")


if __name__ == "__main__":
    main()
