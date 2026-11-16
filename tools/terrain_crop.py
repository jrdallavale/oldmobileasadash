#!/usr/bin/env python3
"""Crop SRTM 1" tile S26W050.hgt to the ride area -> app/src/main/assets/terrain.bin
Format (big endian): int32 magic 'BKT1', double northLat, double westLon, int32 rows, int32 cols,
double stepDeg, then rows*cols int16 heights (row 0 = north). -32768 = void."""
import struct, sys
src, dst = sys.argv[1], sys.argv[2]
N, W, S, E = -25.32, -49.68, -25.60, -49.38
n = 3601
r0, r1 = round((-25 - N) * 3600), round((-25 - S) * 3600)
c0, c1 = round((W + 50) * 3600), round((E + 50) * 3600)
data = open(src, 'rb').read()
rows, cols = r1 - r0 + 1, c1 - c0 + 1
out = bytearray(struct.pack('>4sddiid', b'BKT1', -25 - r0 / 3600, -50 + c0 / 3600, rows, cols, 1 / 3600))
for r in range(r0, r1 + 1):
    off = (r * n + c0) * 2
    out += data[off:off + cols * 2]
open(dst, 'wb').write(out)
print(rows, cols, len(out))
