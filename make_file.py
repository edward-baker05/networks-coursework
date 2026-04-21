#!/usr/bin/env python3
import argparse
import os

def make_file(path: str, size_mb: float) -> None:
    size_bytes = int(size_mb * 1_000_000)
    with open(path, "wb") as f:
        f.write(os.urandom(size_bytes))
    print(f"Created {path} ({size_bytes:,} bytes)")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Create a file of N MB")
    parser.add_argument("path", help="Output file path")
    parser.add_argument("size_mb", type=float, help="File size in MB")
    args = parser.parse_args()
    make_file(args.path, args.size_mb)

