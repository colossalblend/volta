#!/usr/bin/env python3
"""
Volta encrypted subscription links (volta://crypt/...), for VPN providers.

    pip install cryptography
    python3 volta_crypt.py "https://sub.example/abc"
    python3 volta_crypt.py "https://sub.example/abc" --server-type "lte:1,2;home:3" --name "My VPN"
    python3 volta_crypt.py --decrypt-check volta://crypt/...   # only checks the format

The address is encrypted with Volta's public key (ECIES: P-256 ECDH, HKDF-SHA256,
AES-256-GCM), so only the app can open it, and the app then keeps the link hidden:
the user sees the subscription name, never the address.

What goes inside the encryption:
  * by default just the address;
  * with --server-type / --name / --visible, a small JSON object
    {"url": ..., "server-type": ..., "name": ..., "visible_subs": ...}.
    Put server-type inside when it should not be visible or editable in the link.

--visible true makes the app show the address after all (rarely what you want).
"""
import argparse
import base64
import json
import os
import sys

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

# Must match SubCrypto.PUBLIC_KEY_B64 in the app.
PUBLIC_KEY_B64 = (
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEUWxsXxcqdWIU7N6R2yqKdHwBzCgpx9jOwsb8pNV29/W96Nm6"
    "I+WD5nq5e4/7l7tsSaOK8zGtnCw1DTw17uAeNg=="
)
VERSION = 1
KEY_ID = 1
INFO = b"volta-crypt-v1"


def encrypt(plaintext: str, public_key_b64: str = PUBLIC_KEY_B64, key_id: int = KEY_ID) -> str:
    pub = serialization.load_der_public_key(base64.b64decode(public_key_b64))
    eph = ec.generate_private_key(ec.SECP256R1())
    epk = eph.public_key().public_bytes(
        serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint
    )
    shared = eph.exchange(ec.ECDH(), pub)
    key = HKDF(algorithm=hashes.SHA256(), length=32, salt=epk, info=INFO).derive(shared)
    header = bytes([VERSION, key_id]) + epk
    nonce = os.urandom(12)
    body = AESGCM(key).encrypt(nonce, plaintext.encode("utf-8"), header)
    return base64.urlsafe_b64encode(header + nonce + body).rstrip(b"=").decode("ascii")


def main() -> int:
    ap = argparse.ArgumentParser(description="Make a volta://crypt link")
    ap.add_argument("url", nargs="?", help="subscription address (https://...)")
    ap.add_argument("--server-type", default="", help='e.g. "lte:1,2;home:3;auto:9"')
    ap.add_argument("--name", default="", help="subscription name shown in the app")
    ap.add_argument("--visible", choices=["true", "false"], help="let the app show the address")
    ap.add_argument("--outer-name", default="", help="name as a plain parameter of the link")
    ap.add_argument("--decrypt-check", metavar="LINK", help="check a link's format (cannot decrypt)")
    args = ap.parse_args()

    if args.decrypt_check:
        raw = args.decrypt_check.split("crypt/", 1)[-1].split("?", 1)[0]
        data = base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4))
        ok = len(data) > 2 + 65 + 12 + 16 and data[0] == VERSION and data[2] == 4
        print(f"version={data[0]} key_id={data[1]} bytes={len(data)} {'OK' if ok else 'BROKEN'}")
        return 0 if ok else 1

    if not args.url or not args.url.lower().startswith(("https://", "http://")):
        ap.error("give the subscription address, starting with https://")

    if args.server_type or args.name or args.visible:
        payload = {"url": args.url}
        if args.server_type:
            payload["server-type"] = args.server_type
        if args.name:
            payload["name"] = args.name
        if args.visible:
            payload["visible_subs"] = args.visible
        plaintext = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    else:
        plaintext = args.url

    link = "volta://crypt/" + encrypt(plaintext)
    if args.outer_name:
        from urllib.parse import quote
        link += "?name=" + quote(args.outer_name)
    print(link)
    return 0


if __name__ == "__main__":
    sys.exit(main())
