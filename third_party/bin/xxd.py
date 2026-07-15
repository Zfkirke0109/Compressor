"""Minimal xxd '--include' emulation for the libvmaf meson build on Windows.

Usage: xxd --include INPUT OUTPUT
Emits a C array named after the INPUT path exactly as passed (non-alphanumeric
characters replaced with '_'), matching vim-xxd's -i naming so libvmaf's
model.c extern declarations (e.g. src_vmaf_v0_6_1_json) resolve.
"""
import re
import sys


def main() -> int:
    args = [a for a in sys.argv[1:] if a not in ("--include", "-i")]
    if len(args) != 2:
        sys.stderr.write("usage: xxd --include INPUT OUTPUT\n")
        return 2
    input_path, output_path = args
    name = re.sub(r"[^A-Za-z0-9]", "_", input_path)
    with open(input_path, "rb") as fh:
        data = fh.read()
    lines = [f"unsigned char {name}[] = {{"]
    for offset in range(0, len(data), 12):
        chunk = data[offset:offset + 12]
        lines.append("  " + ", ".join(f"0x{b:02x}" for b in chunk) + ",")
    lines.append("};")
    lines.append(f"unsigned int {name}_len = {len(data)};")
    with open(output_path, "w", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
