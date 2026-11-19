#!/usr/bin/env python3
"""Independent oracle for core:crypto test vectors (SPEC §3-4).

Computes the expected bytes from the specification with implementations that do not go through
libsodium, so the Kotlin code (which does) is checked against something that cannot share its bugs:

  BLAKE2b (generichash, kdf, master id, content hash)  hashlib
  XChaCha20-Poly1305                                    HChaCha20 below + ChaCha20Poly1305 from cryptography (OpenSSL)
  Argon2id                                              argon2-cffi (reference implementation)
  CBOR                                                  cbor2, canonical mode

Usage: python3 -I generate_vectors.py <output.json>
Needs: cryptography, cbor2, argon2-cffi.
"""

import base64
import hashlib
import json
import struct
import sys
import unicodedata
import zlib

import cbor2
from argon2.low_level import Type, hash_secret_raw
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

M32 = 0xFFFFFFFF


def rotl(v, c):
    return ((v << c) & M32) | (v >> (32 - c))


def quarter(s, a, b, c, d):
    s[a] = (s[a] + s[b]) & M32; s[d] = rotl(s[d] ^ s[a], 16)
    s[c] = (s[c] + s[d]) & M32; s[b] = rotl(s[b] ^ s[c], 12)
    s[a] = (s[a] + s[b]) & M32; s[d] = rotl(s[d] ^ s[a], 8)
    s[c] = (s[c] + s[d]) & M32; s[b] = rotl(s[b] ^ s[c], 7)


def hchacha20(key, nonce16):
    s = [0x61707865, 0x3320646E, 0x79622D32, 0x6B206574]
    s += list(struct.unpack("<8I", key)) + list(struct.unpack("<4I", nonce16))
    for _ in range(10):
        quarter(s, 0, 4, 8, 12); quarter(s, 1, 5, 9, 13); quarter(s, 2, 6, 10, 14); quarter(s, 3, 7, 11, 15)
        quarter(s, 0, 5, 10, 15); quarter(s, 1, 6, 11, 12); quarter(s, 2, 7, 8, 13); quarter(s, 3, 4, 9, 14)
    return struct.pack("<8I", *(s[0:4] + s[12:16]))


def xchacha_seal(key, nonce24, plaintext, ad):
    subkey = hchacha20(key, nonce24[:16])
    return ChaCha20Poly1305(subkey).encrypt(b"\0" * 4 + nonce24[16:], plaintext, ad)


def self_check():
    # draft-irtf-cfrg-xchacha-03, section 2.2.1
    out = hchacha20(bytes(range(32)), bytes.fromhex("000000090000004a0000000031415927"))
    assert out.hex() == "82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc", "HChaCha20 self-check"
    # draft-irtf-cfrg-xchacha-03, appendix A.3.1 (tag only)
    pt = (b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, "
          b"sunscreen would be it.")
    sealed = xchacha_seal(bytes(range(0x80, 0xA0)), bytes(range(0x40, 0x58)), pt,
                          bytes.fromhex("50515253c0c1c2c3c4c5c6c7"))
    assert sealed[-16:].hex() == "c0875924c1c7987947deafd8780acf49", "XChaCha20-Poly1305 self-check"


def b64url(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def kdf(key, subkey_id, context):
    # crypto_kdf_derive_from_key: BLAKE2b keyed, salt = id (LE64) || 0^8, personal = context || 0^8
    return hashlib.blake2b(b"", digest_size=32, key=key,
                           salt=struct.pack("<Q", subkey_id) + b"\0" * 8, person=context + b"\0" * 8).digest()


def cbor(obj):
    return cbor2.dumps(obj, canonical=True)


def nonce(seed):
    return bytes((seed + i) % 256 for i in range(24))


def pattern(n):
    return bytes(i % 251 for i in range(n))


def main(out_path):
    self_check()
    v = {}

    master = bytes(range(32))
    salt = bytes(range(0x10, 0x20))
    file_id = bytes(range(0xA0, 0xB0))
    dek = bytes(range(0x80, 0xA0))

    meta_key = kdf(master, 1, b"cofmeta_")
    index_key = kdf(master, 2, b"cofindex")
    wrap_key = kdf(master, 3, b"cofwrap_")
    master_id = hashlib.blake2b(b"coffer/v1/master-id", digest_size=16, key=master).digest()
    v["master"] = master.hex()
    v["subkeys"] = {"meta": meta_key.hex(), "index": index_key.hex(), "wrap": wrap_key.hex()}
    v["masterId"] = master_id.hex()

    password = unicodedata.normalize("NFC", "correct horse battery staple café")
    v["argon2id"] = []
    for ops, mem in [(1, 8 * 1024 * 1024), (3, 64 * 1024 * 1024)]:
        kek = hash_secret_raw(password.encode(), salt, time_cost=ops, memory_cost=mem // 1024,
                              parallelism=1, hash_len=32, type=Type.ID, version=19)
        v["argon2id"].append({"password": password, "salt": salt.hex(), "ops": ops, "mem": mem, "kek": kek.hex()})
    kek = bytes.fromhex(v["argon2id"][0]["kek"])

    n_master = nonce(0x40)
    wrapped_master = n_master + xchacha_seal(kek, n_master, master, b"coffer/v1/wrap/master")
    n_dek = nonce(0x60)
    wrapped_dek = n_dek + xchacha_seal(wrap_key, n_dek, dek, b"coffer/v1/wrap/dek" + file_id)
    v["wrap"] = {
        "kek": kek.hex(), "masterNonce": n_master.hex(), "wrappedMaster": wrapped_master.hex(),
        "fileId": file_id.hex(), "dek": dek.hex(), "dekNonce": n_dek.hex(), "wrappedDek": wrapped_dek.hex(),
    }

    v["chunks"] = []
    for index, last, size, seed in [(0, False, 10, 0x01), (513, True, 1000, 0x02), (0, True, 0, 0x03)]:
        pt = pattern(size)
        n = nonce(seed)
        ad = b"coffer/v1/chunk" + file_id + struct.pack(">Q", index) + bytes([1 if last else 0])
        v["chunks"].append({"index": index, "isLast": last, "plainSize": size, "nonce": n.hex(),
                            "sealed": (n + xchacha_seal(dek, n, pt, ad)).hex()})

    v["contentHash"] = [{"size": n, "hash": hashlib.blake2b(pattern(n), digest_size=32).hexdigest()}
                        for n in [0, 3, 1048576, 3 * 1048576 + 5]]

    header = {1: 1, 2: 1762531200000, 3: salt, 4: 3, 5: 64 * 1024 * 1024, 6: wrapped_master, 7: master_id}
    v["vaultHeader"] = {"createdAt": 1762531200000, "ops": 3, "mem": 64 * 1024 * 1024,
                        "cbor": cbor(header).hex(), "caption": "coffer:v1:vault:" + b64url(cbor(header))}

    def sealed_caption(kind, payload, seed):
        body = cbor(payload)
        n = nonce(seed)
        enc = n + xchacha_seal(meta_key, n, body, b"coffer/v1/" + kind.encode())
        return {"kind": kind, "nonce": n.hex(), "cbor": body.hex(), "caption": f"coffer:v1:{kind}:{b64url(enc)}"}

    folder_id = bytes(range(0xC0, 0xD0))
    op_id = bytes(range(0xD0, 0xE0))
    content_hash = bytes(range(32))[::-1]
    v["records"] = {
        "blobFirstRoot": sealed_caption("blob", {
            1: file_id, 2: 0, 3: 3, 4: 1048576, 5: 1_100_000_000, 6: "Отчёт 2026.pdf", 7: None,
            8: "application/pdf", 9: 1762531200123, 10: 1762531200456, 11: content_hash, 12: wrapped_dek}, 0x10),
        "blobFirstInFolder": sealed_caption("blob", {
            1: file_id, 2: 0, 3: 1, 4: 1048576, 5: 0, 6: "empty.txt", 7: folder_id,
            8: "text/plain; charset=utf-8", 9: 1, 10: 2, 11: content_hash, 12: wrapped_dek}, 0x11),
        "blobLaterPart": sealed_caption("blob", {1: file_id, 2: 2, 3: 3, 4: 1048576, 5: 1_100_000_000}, 0x12),
        "folderCreate": sealed_caption("op", {1: op_id, 2: "folder.create", 3: 1000, 5: folder_id, 6: None, 7: "Docs"}, 0x20),
        "folderRename": sealed_caption("op", {1: op_id, 2: "folder.rename", 3: 1001, 5: folder_id, 7: "Документы"}, 0x21),
        "folderMove": sealed_caption("op", {1: op_id, 2: "folder.move", 3: 1002, 5: folder_id, 6: file_id}, 0x22),
        "folderDelete": sealed_caption("op", {1: op_id, 2: "folder.delete", 3: 1003, 5: folder_id}, 0x23),
        "fileRename": sealed_caption("op", {1: op_id, 2: "file.rename", 3: 1004, 4: file_id, 7: "new name.bin"}, 0x24),
        "fileMove": sealed_caption("op", {1: op_id, 2: "file.move", 3: 1005, 4: file_id, 5: None}, 0x25),
        "fileDelete": sealed_caption("op", {1: op_id, 2: "file.delete", 3: 1006, 4: file_id}, 0x26),
        "index": sealed_caption("index", {1: folder_id, 2: 123456789012, 3: 1762531200789, 4: 98765}, 0x30),
    }
    # Format v2 (SPEC §4.5): captions padded to a fixed size per kind, content padded with PADMÉ.
    def padme(size):
        # Nikitin et al. 2019, written out from the paper with plain loops rather than bit tricks.
        if size < 2:
            return size
        e = 0
        while 2 ** (e + 1) <= size:
            e += 1
        s = 0
        while 2 ** (s + 1) <= e:
            s += 1
        s += 1
        step = 2 ** (e - s)
        return -(-size // step) * step

    def parts_of(content_size):
        chunks = max(1, -(-content_size // 1048576))
        return -(-chunks // 512)

    rooms = {"blob": 600, "op": 352, "index": 64}

    def v2_caption(kind, payload, seed):
        body = cbor(payload)
        framed = struct.pack(">H", len(body)) + body + b"\0" * (rooms[kind] - len(body))
        n = nonce(seed)
        enc = n + xchacha_seal(meta_key, n, framed, b"coffer/v2/" + kind.encode())
        return {"kind": kind, "nonce": n.hex(), "cbor": body.hex(), "caption": f"coffer:v2:{kind}:{b64url(enc)}"}

    big = 1_100_000_000
    big_parts = parts_of(padme(big))
    padded_plain = pattern(1000)
    n_padded = nonce(0x90)
    padded_content = padded_plain + b"\0" * (padme(1000) - 1000)
    chunk_ad = b"coffer/v1/chunk" + file_id + struct.pack(">Q", 0) + bytes([1])
    v["v2"] = {
        "padme": [[size, padme(size)] for size in [0, 1, 2, 3, 7, 8, 9, 100, 1000, 1023, 1024, 1025, 40_000,
                                                  3_500_000, 1048576, 1048577, big, 4_000_000_000,
                                                  2 ** 50 - 1, 2 ** 50]],
        "rooms": rooms,
        "records": {
            "blobFirstPadded": v2_caption("blob", {
                1: file_id, 2: 0, 3: big_parts, 4: 1048576, 5: big, 6: "Отчёт 2026.pdf", 7: None,
                8: "application/pdf", 9: 1762531200123, 10: 1762531200456, 11: content_hash, 12: wrapped_dek, 13: True}, 0x50),
            "blobLaterPadded": v2_caption("blob", {1: file_id, 2: big_parts - 1, 3: big_parts, 4: 1048576, 5: big, 13: True}, 0x51),
            "blobFirstPlain": v2_caption("blob", {
                1: file_id, 2: 0, 3: 1, 4: 1048576, 5: 0, 6: "empty.txt", 7: folder_id,
                8: "text/plain; charset=utf-8", 9: 1, 10: 2, 11: content_hash, 12: wrapped_dek}, 0x52),
            "fileRename": v2_caption("op", {1: op_id, 2: "file.rename", 3: 1004, 4: file_id, 7: "new name.bin"}, 0x53),
            "index": v2_caption("index", {1: folder_id, 2: 123456789012, 3: 1762531200789, 4: 98765}, 0x54),
        },
        # A 1000-byte file padded to one chunk of padme(1000): the file, then zeros, encrypted as one.
        "paddedContent": {"plainSize": 1000, "contentSize": padme(1000), "nonce": n_padded.hex(),
                          "sealed": (n_padded + xchacha_seal(dek, n_padded, padded_content, chunk_ad)).hex()},
    }
    assert v["v2"]["padme"][-1] == [2 ** 50, 2 ** 50] and padme(2 ** 50 - 1) <= 2 ** 50

    # Format v3 (SPEC §4.6): several operations in one message, framed like v2 at one size.
    ops_room = 2998

    def v3_pack(ops, seed):
        body = cbor({1: ops})
        assert len(body) <= ops_room, len(body)
        framed = struct.pack(">H", len(body)) + body + b"\0" * (ops_room - len(body))
        n = nonce(seed)
        enc = n + xchacha_seal(meta_key, n, framed, b"coffer/v3/ops")
        caption = f"coffer:v3:ops:{b64url(enc)}"
        assert len(caption) == 4068 and len(caption) <= 4096, len(caption)
        # The ops as their CBOR maps, labels as strings, so the test builds them without this script's help.
        listed = [{str(k): (val.hex() if isinstance(val, bytes) else val) for k, val in op.items()} for op in ops]
        return {"ops": listed, "nonce": n.hex(), "cbor": body.hex(), "caption": caption}

    def nth_id(k, base):
        return bytes([base, k]) + bytes(range(14))

    def most_that_fit(make):
        count = 1
        while len(cbor({1: [make(k) for k in range(count + 1)]})) <= ops_room:
            count += 1
        return count

    folder_id_2 = bytes(range(0xE0, 0xF0))
    worst_name_v3 = "я" * 127 + "x"
    worst_folder = lambda k: {1: nth_id(k, 0x01), 2: "folder.create", 3: 2 ** 62, 5: nth_id(k, 0x02), 6: folder_id,
                              7: worst_name_v3}
    small_delete = lambda k: {1: nth_id(k, 0x03), 2: "file.delete", 3: 1762531200000 + k, 4: nth_id(k, 0x04)}
    worst_count = most_that_fit(worst_folder)
    delete_count = most_that_fit(small_delete)
    v["v3"] = {
        "room": ops_room,
        "packs": {
            "mixed": v3_pack([
                {1: nth_id(0, 0x05), 2: "folder.create", 3: 1762531200000, 5: folder_id, 6: None, 7: "Docs"},
                {1: nth_id(1, 0x05), 2: "folder.create", 3: 1762531200000, 5: folder_id_2, 6: folder_id, 7: "Фото 2026"},
                {1: nth_id(2, 0x05), 2: "file.rename", 3: 1762531200001, 4: file_id, 7: "new name.bin"},
                {1: nth_id(3, 0x05), 2: "folder.move", 3: 1762531200002, 5: folder_id_2, 6: None},
                {1: nth_id(4, 0x05), 2: "file.move", 3: 1762531200003, 4: file_id, 5: folder_id_2},
                {1: nth_id(5, 0x05), 2: "folder.rename", 3: 1762531200004, 5: folder_id, 7: "Документы"},
            ], 0xA0),
            "one": v3_pack([{1: op_id, 2: "file.move", 3: 1005, 4: file_id, 5: None}], 0xA1),
            "worstFull": v3_pack([worst_folder(k) for k in range(worst_count)], 0xA2),
            "deletesFull": v3_pack([small_delete(k) for k in range(delete_count)], 0xA3),
        },
        "worstCount": worst_count,
        "deleteCount": delete_count,
    }
    assert worst_count == 8 and delete_count >= 24, (worst_count, delete_count)

    # Device-only sealing of the local index (SPEC §8): not part of the Telegram format, checked all the same.
    local_key = kdf(master, 1, b"coflocal")
    local_body = cbor({1: "Отчёт 2026.pdf", 2: None, 3: "application/pdf", 4: 1762531200123, 5: 1762531200456,
                       6: content_hash, 7: wrapped_dek})
    n_local = nonce(0x70)
    v["local"] = {"key": local_key.hex(), "nonce": n_local.hex(), "cbor": local_body.hex(),
                  "sealed": (n_local + xchacha_seal(local_key, n_local, local_body, b"coffer/local/v1/file" + file_id)).hex()}
    # A folder (name, parent) and a queued upload with its target folder.
    folder_body = cbor({1: "Налоги", 2: folder_id})
    n_folder = nonce(0x78)
    v["local"]["folder"] = {"nonce": n_folder.hex(), "cbor": folder_body.hex(),
                            "sealed": (n_folder + xchacha_seal(local_key, n_folder, folder_body,
                                                               b"coffer/local/v1/folder" + file_id)).hex()}
    upload_body = cbor({1: "content://x/1", 2: "a.txt", 3: None, 4: folder_id})
    n_upload = nonce(0x80)
    v["local"]["upload"] = {"nonce": n_upload.hex(), "cbor": upload_body.hex(),
                            "sealed": (n_upload + xchacha_seal(local_key, n_upload, upload_body,
                                                               b"coffer/local/v1/upload" + b"u1")).hex()}
    # A file's preview for the browser: the picture's bytes as they are, sealed under the file's id.
    preview_body = b"RIFF\x1a\x00\x00\x00WEBPVP8 " + bytes(range(14))
    n_preview = nonce(0x88)
    v["local"]["preview"] = {"nonce": n_preview.hex(), "plain": preview_body.hex(),
                             "sealed": (n_preview + xchacha_seal(local_key, n_preview, preview_body,
                                                                 b"coffer/local/v1/preview" + file_id)).hex()}
    # A song's waveform for the player: 80 bar heights, sealed under the file's id.
    waveform_body = bytes((i * 37) % 256 for i in range(80))
    n_waveform = nonce(0x98)
    v["local"]["waveform"] = {"nonce": n_waveform.hex(), "plain": waveform_body.hex(),
                              "sealed": (n_waveform + xchacha_seal(local_key, n_waveform, waveform_body,
                                                                   b"coffer/local/v1/waveform" + file_id)).hex()}

    # Index snapshot (SPEC §4.4): state labels, raw deflate, content encryption with IndexKey.
    snapshot_id = bytes(range(0xE0, 0xF0))
    sub_folder = bytes(range(0x10, 0x20))
    empty_file = bytes(range(0x30, 0x40))
    orphan_file = bytes(range(0x50, 0x60))
    gone_a, gone_b, gone_folder = bytes(range(0x60, 0x70)), bytes(range(0x70, 0x80)), bytes(range(0x80, 0x90))
    last_applied = 7 << 20

    def state_file(fid, size, name, folder, damaged=False, parts=None):
        chunks = max(1, -(-size // 1048576))  # SPEC §3.4: ceil, at least one chunk
        entry = {1: fid, 3: -(-chunks // 512), 4: 1048576, 5: size, 6: name, 7: folder,
                 8: "application/pdf", 9: 1762531200123, 10: 1762531200456, 11: content_hash, 12: wrapped_dek,
                 13: parts if parts is not None else {}}
        if damaged:
            entry[14] = True
        return entry

    def state(override=None):
        st = {
            1: last_applied,
            2: [{1: sub_folder, 2: folder_id, 3: "2025"}, {1: folder_id, 2: None, 3: "Налоги"}],
            3: [state_file(empty_file, 0, "empty.txt", None, damaged=True),
                state_file(file_id, 1048577, "Отчёт.pdf", folder_id, parts={0: 1 << 20})],
            4: [{1: orphan_file, 13: {1: 3 << 20}}],
            5: [gone_a, gone_b],
            6: [gone_folder],
        }
        st.update(override or {})
        return st

    state_cbor = cbor(state())
    squeezer = zlib.compressobj(9, zlib.DEFLATED, -15)
    deflated = squeezer.compress(state_cbor) + squeezer.flush()
    assert zlib.decompress(deflated, -15) == state_cbor
    n_snap = nonce(0x90)
    snap_ad = b"coffer/v1/chunk" + snapshot_id + struct.pack(">Q", 0) + bytes([1])
    document = n_snap + xchacha_seal(index_key, n_snap, deflated, snap_ad)
    created = 1762531200999
    v["snapshot"] = {
        "snapshotId": snapshot_id.hex(), "lastApplied": last_applied, "createdAt": created,
        "cbor": state_cbor.hex(), "deflated": deflated.hex(), "nonce": n_snap.hex(), "document": document.hex(),
        "caption": sealed_caption("index", {1: snapshot_id, 2: last_applied, 3: created, 4: len(deflated)}, 0x91),
        "ids": {"subFolder": sub_folder.hex(), "emptyFile": empty_file.hex(), "orphanFile": orphan_file.hex(),
                "goneA": gone_a.hex(), "goneB": gone_b.hex(), "goneFolder": gone_folder.hex()},
    }

    # Each breaks exactly one rule of the strict reading; the good state above must decode.
    good = state()
    unsorted_parts = state_file(file_id, 3 * 512 * 1048576, "Отчёт.pdf", folder_id, parts={2: 3 << 20, 0: 1 << 20})
    bad = {
        "folders out of order": cbor(state({2: good[2][::-1]})),
        "files out of order": cbor(state({3: good[3][::-1]})),
        "deleted ids out of order": cbor(state({5: [gone_b, gone_a]})),
        "duplicate deleted id": cbor(state({5: [gone_a, gone_a]})),
        "damaged written as false": cbor(state({3: [{**good[3][0], **{14: False}}, good[3][1]]})),
        "part index past part count": cbor(state({3: [good[3][0], state_file(file_id, 1048577, "Отчёт.pdf", folder_id, parts={1: 1 << 20})]})),
        "part count does not match size": cbor(state({3: [good[3][0], {**good[3][1], **{3: 2}}]})),
        "wrong chunk size": cbor(state({3: [good[3][0], {**good[3][1], **{4: 65536}}]})),
        "orphan of a known file": cbor(state({4: [{1: file_id, 13: {1: 3 << 20}}]})),
        "orphan of a deleted file": cbor(state({4: [{1: gone_a, 13: {1: 3 << 20}}]})),
        "folder parent missing": cbor(state({2: [{1: sub_folder, 3: "2025"}, good[2][1]]})),
        "unknown label": cbor(state({7: 1})),
        "empty name": cbor(state({2: [{1: sub_folder, 2: folder_id, 3: ""}, good[2][1]]})),
        # Map keys out of order: written by hand, since canonical mode would sort them.
        "parts out of order": cbor2.dumps({1: last_applied, 2: good[2], 3: [good[3][0], unsorted_parts], 4: good[4], 5: good[5], 6: good[6]}),
    }
    assert cbor2.loads(bad["parts out of order"])[3][1][13] == {2: 3 << 20, 0: 1 << 20}
    assert cbor(cbor2.loads(bad["parts out of order"])) != bad["parts out of order"]
    v["snapshot"]["bad"] = [{"why": why, "cbor": body.hex()} for why, body in bad.items()]

    v["ids"] = {"fileId": file_id.hex(), "folderId": folder_id.hex(), "opId": op_id.hex(), "contentHash": content_hash.hex()}

    # Worst case for the caption budget (SPEC §4.2): 255-byte name, 127-byte mime, 8-byte integers.
    worst_name = "я" * 127 + "x"
    worst_mime = "application/" + "x" * (127 - len("application/"))
    assert len(worst_name.encode()) == 255 and len(worst_mime) == 127
    big = 2 ** 62
    max_size = 2 ** 50  # CofferFormat.MAX_PLAIN_SIZE
    max_parts = max_size // 1048576 // 512
    worst = sealed_caption("blob", {
        1: file_id, 2: 0, 3: max_parts, 4: 1048576, 5: max_size, 6: worst_name, 7: folder_id,
        8: worst_mime, 9: big, 10: big, 11: content_hash, 12: wrapped_dek}, 0x50)
    worst.update({"name": worst_name, "mime": worst_mime, "big": big, "plainSize": max_size, "partCount": max_parts})
    assert len(worst["caption"]) <= 1024, len(worst["caption"])
    v["worstCaption"] = worst

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(v, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"wrote {out_path}; worst-case caption {len(worst['caption'])} chars")


if __name__ == "__main__":
    main(sys.argv[1])
