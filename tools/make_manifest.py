"""assets/models altindaki tum dosyalari "goreli_yol<TAB>bayt" olarak manifest.txt'ye yazar.

Uygulama ilk acilista bu listeye gore modelleri kopyalar ve boyutlarini dogrular.
Kullanim: python make_manifest.py <assets/models>
"""
import sys
from pathlib import Path


def main() -> None:
    root = Path(sys.argv[1])
    lines = []
    total = 0
    for p in sorted(root.rglob("*")):
        if not p.is_file() or p.name == "manifest.txt":
            continue
        rel = p.relative_to(root).as_posix()
        if "\t" in rel or "\n" in rel:
            raise SystemExit(f"Gecersiz dosya adi: {rel!r}")
        size = p.stat().st_size
        total += size
        lines.append(f"{rel}\t{size}\n")
    (root / "manifest.txt").write_text("".join(lines), encoding="utf-8", newline="\n")
    print(f"{len(lines)} dosya, {total / 1048576:.0f} MB")


if __name__ == "__main__":
    main()
