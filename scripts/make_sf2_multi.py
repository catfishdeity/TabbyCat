#!/usr/bin/env python3
"""Build SF2 SoundFont(s) from all WAVs in a directory.

Like make_sf2.py, but splits into multiple SF2 files if there are more than
128 samples (one file per 128-note bank, suffixed _2, _3, etc.).

Usage:
    make_sf2_multi.py -d <directory> -o <output>

Also importable: call build_sf2_multi(wav_dir, out_path) directly.
"""

import argparse, struct, wave, os, glob, array
from pathlib import Path

# ---------------------------------------------------------------------------
# helpers (shared with make_sf2.py)
# ---------------------------------------------------------------------------

def fourcc(s):
    return s.encode('ascii')

def chunk(tag, data):
    data = bytes(data)
    if len(data) % 2:
        data += b'\x00'
    return fourcc(tag) + struct.pack('<I', len(data)) + data

def list_chunk(list_type, *sub_chunks):
    inner = fourcc(list_type) + b''.join(sub_chunks)
    return b'LIST' + struct.pack('<I', len(inner)) + inner

def str20(s):
    b = s.encode('ascii', errors='replace')[:19]
    return b.ljust(20, b'\x00')

# ---------------------------------------------------------------------------
# single-bank writer (max 128 samples)
# ---------------------------------------------------------------------------

def _write_bank(samples: list, out_path: str, name: str) -> None:
    N = len(samples)
    assert N <= 128

    GUARD = 46
    smpl_data = bytearray(GUARD * 2)
    smp_start, smp_end = [], []

    for s in samples:
        st = len(smpl_data) // 2
        smpl_data += s['pcm'].tobytes()
        en = len(smpl_data) // 2
        smpl_data += bytearray(GUARD * 2)
        smp_start.append(st)
        smp_end.append(en)

    shdr_data = bytearray()
    for i, s in enumerate(samples):
        shdr_data += str20(s['name'][:19])
        shdr_data += struct.pack('<IIIII',
            smp_start[i], smp_end[i],
            smp_start[i], smp_end[i] - 1,
            s['rate'])
        shdr_data += struct.pack('<BbHH', i, 0, 0, 1)
    shdr_data += str20('EOS')
    shdr_data += struct.pack('<IIIIIBbHH', 0, 0, 0, 0, 0, 0, 0, 0, 0)

    KEYRANGE_GEN = 43
    SAMPLEID_GEN = 53
    igen_data = bytearray()
    ibag_data = bytearray()
    igen_idx = imod_idx = 0

    for i in range(N):
        ibag_data += struct.pack('<HH', igen_idx, imod_idx)
        igen_data += struct.pack('<HBB', KEYRANGE_GEN, i, i)
        igen_data += struct.pack('<HH',  SAMPLEID_GEN, i)
        igen_idx += 2

    ibag_data += struct.pack('<HH', igen_idx, imod_idx)
    igen_data += struct.pack('<HH', 0, 0)
    imod_data  = struct.pack('<HHhHH', 0, 0, 0, 0, 0)

    inst_data  = bytearray()
    inst_data += str20(name[:19])
    inst_data += struct.pack('<H', 0)
    inst_data += str20('EOI')
    inst_data += struct.pack('<H', N)

    INSTRUMENT_GEN = 41
    pgen_data  = bytearray()
    pgen_data += struct.pack('<HH', INSTRUMENT_GEN, 0)
    pgen_data += struct.pack('<HH', 0, 0)

    pbag_data  = bytearray()
    pbag_data += struct.pack('<HH', 0, 0)
    pbag_data += struct.pack('<HH', 1, 0)

    pmod_data  = struct.pack('<HHhHH', 0, 0, 0, 0, 0)

    phdr_data  = bytearray()
    phdr_data += str20(name[:19])
    phdr_data += struct.pack('<HHH', 0, 128, 0)
    phdr_data += struct.pack('<III', 0, 0, 0)
    phdr_data += str20('EOP')
    phdr_data += struct.pack('<HHH', 255, 255, 1)
    phdr_data += struct.pack('<III', 0, 0, 0)

    name_bytes = name.encode('ascii', errors='replace')[:19] + b'\x00'
    info = list_chunk('INFO',
        chunk('ifil', struct.pack('<HH', 2, 1)),
        chunk('isng', b'EMU8000\x00'),
        chunk('INAM', name_bytes),
    )
    sdta = list_chunk('sdta', chunk('smpl', smpl_data))
    pdta = list_chunk('pdta',
        chunk('phdr', phdr_data),
        chunk('pbag', pbag_data),
        chunk('pmod', pmod_data),
        chunk('pgen', pgen_data),
        chunk('inst', inst_data),
        chunk('ibag', ibag_data),
        chunk('imod', imod_data),
        chunk('igen', igen_data),
        chunk('shdr', shdr_data),
    )

    sfbk_inner = fourcc('sfbk') + info + sdta + pdta
    riff = b'RIFF' + struct.pack('<I', len(sfbk_inner)) + sfbk_inner

    with open(out_path, 'wb') as f:
        f.write(riff)

    print(f'Written: {out_path}  ({len(riff):,} bytes, notes 0–{N-1})')

# ---------------------------------------------------------------------------
# multi-bank entry point
# ---------------------------------------------------------------------------

def build_sf2_multi(wav_dir: str, out_path: str) -> None:
    DIR = os.path.abspath(wav_dir)
    out_path = out_path if out_path.lower().endswith('.sf2') else out_path + '.sf2'

    if not os.path.isdir(DIR):
        raise SystemExit(f'Not a directory: {DIR}')

    wav_paths = sorted(glob.glob(os.path.join(DIR, '*.wav')))
    if not wav_paths:
        raise SystemExit(f'No WAV files found in {DIR}')

    samples = []
    for path in wav_paths:
        with wave.open(path, 'rb') as wf:
            nch = wf.getnchannels()
            sw   = wf.getsampwidth()
            rate = wf.getframerate()
            raw  = wf.readframes(wf.getnframes())

        if sw == 1:
            pcm = array.array('h', [(b - 128) << 8 for b in array.array('B', raw)])
        elif sw == 2:
            pcm = array.array('h', raw)
        elif sw == 3:
            pcm = array.array('h')
            for k in range(0, len(raw), 3):
                val = struct.unpack('<i', raw[k:k+3] + (b'\xff' if raw[k+2] & 0x80 else b'\x00'))[0]
                pcm.append(val >> 8)
        elif sw == 4:
            pcm = array.array('h', [s >> 16 for s in array.array('i', raw)])
        else:
            raise SystemExit(f'Unsupported sample width {sw} bytes in {path}')

        if nch > 1:
            pcm = array.array('h', pcm[::nch])

        samples.append({
            'name': os.path.splitext(os.path.basename(path))[0],
            'pcm':  pcm,
            'rate': rate,
        })

    N = len(samples)
    n_banks = (N + 127) // 128
    print(f'Loaded {N} samples from {DIR} → {n_banks} bank(s)')

    stem = Path(out_path).stem
    parent = Path(out_path).parent

    for b in range(n_banks):
        chunk_samples = samples[b*128 : (b+1)*128]
        if n_banks == 1:
            path = str(parent / f'{stem}.sf2')
            label = stem
        else:
            path = str(parent / f'{stem}_{b+1}.sf2')
            label = f'{stem[:17]}_{b+1}'  # keep within 19-char SF2 name limit
        _write_bank(chunk_samples, path, label)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description='Build SF2(s) from WAVs, splitting into multiple banks if needed.'
    )
    parser.add_argument('-d', required=True, metavar='DIR',  help='Directory containing WAV files')
    parser.add_argument('-o', required=True, metavar='FILE', help='Output SF2 filename (base name for splits)')
    args = parser.parse_args()
    build_sf2_multi(args.d, args.o)
