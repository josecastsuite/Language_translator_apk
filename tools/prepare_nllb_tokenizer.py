"""NLLB tokenizer.json dosyasini telefonda hizli okunacak iki kucuk dosyaya cevirir.

Cikti:
  nllb_vocab.txt   : her satir bir token, satir numarasi = token id
  nllb_merges.txt  : her satir "idA idB", satir sirasi = birlestirme onceligi

Kullanim: python prepare_nllb_tokenizer.py <tokenizer.json> <cikti_klasoru>
"""
import json
import sys
from pathlib import Path


def main() -> None:
    src = Path(sys.argv[1])
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)

    tok = json.loads(src.read_text(encoding="utf-8"))
    model = tok["model"]
    if model["type"] != "BPE":
        raise SystemExit(f"Beklenmeyen tokenizer tipi: {model['type']}")

    vocab = dict(model["vocab"])
    for added in tok["added_tokens"]:
        vocab[added["content"]] = added["id"]

    size = max(vocab.values()) + 1
    id2tok = [None] * size
    for piece, idx in vocab.items():
        if "\n" in piece or "\r" in piece:
            raise SystemExit(f"Token satir sonu iceriyor: {piece!r}")
        if id2tok[idx] is not None and id2tok[idx] != piece:
            raise SystemExit(f"Ayni id iki farkli token: {idx}")
        id2tok[idx] = piece
    missing = [i for i, p in enumerate(id2tok) if p is None]
    if missing:
        raise SystemExit(f"Bos id'ler var: {missing[:10]}")

    with open(out / "nllb_vocab.txt", "w", encoding="utf-8", newline="\n") as f:
        for piece in id2tok:
            f.write(piece + "\n")

    with open(out / "nllb_merges.txt", "w", encoding="utf-8", newline="\n") as f:
        for merge in model["merges"]:
            a, b = merge.split(" ") if isinstance(merge, str) else merge
            if (a + b) not in vocab:
                raise SystemExit(f"Birlesim sonucu sozlukte yok: {a!r}+{b!r}")
            f.write(f"{vocab[a]} {vocab[b]}\n")

    print(f"vocab={size} merges={len(model['merges'])}")


if __name__ == "__main__":
    main()
