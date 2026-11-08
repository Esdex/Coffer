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
    # Device-only sealing of the local index (SPEC §8): not part of the Telegram format, checked all the same.
    local_key = kdf(master, 1, b"coflocal")
    local_body = cbor({1: "Отчёт 2026.pdf", 2: None, 3: "application/pdf", 4: 1762531200123, 5: 1762531200456,
                       6: content_hash, 7: wrapped_dek})
    n_local = nonce(0x70)
    v["local"] = {"key": local_key.hex(), "nonce": n_local.hex(), "cbor": local_body.hex(),
                  "sealed": (n_local + xchacha_seal(local_key, n_local, local_body, b"coffer/local/v1/file" + file_id)).hex()}

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
