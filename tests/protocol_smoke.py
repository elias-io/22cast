#!/usr/bin/env python3
"""Check the actual receiver, including identity signatures and encrypted pairing.

Requires the optional Python `cryptography` package. Run local.py --headless first.
This does not substitute for a physical iPhone-to-TV playback test.
"""
import hashlib
import argparse
import json
import plistlib
import socket
import urllib.request
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--port',type=int)
    parser.add_argument('--name',default='22Cast Living Room')
    args=parser.parse_args()
    state = dict(status='ready',port=args.port,name=args.name) if args.port else json.load(urllib.request.urlopen('http://127.0.0.1:2244/api/state'))
    assert state['status'] == 'ready', state
    sock = socket.create_connection(('127.0.0.1', int(state['port'])), timeout=4)
    reader = sock.makefile('rb')
    sequence = 0
    def request(path, body=b'', method='POST'):
        nonlocal sequence
        sequence += 1
        content_type = 'Content-Type: application/octet-stream\r\n' if body else ''
        headers = f'{method} {path} RTSP/1.0\r\nCSeq: {sequence}\r\nUser-Agent: AirPlay/745.13.4\r\n{content_type}Content-Length: {len(body)}\r\n\r\n'
        sock.sendall(headers.encode() + body)
        line = reader.readline().decode().strip()
        assert '200' in line, (path, line)
        fields = {}
        while True:
            line = reader.readline()
            if line in (b'\r\n', b'\n', b''):
                break
            key, value = line.decode().split(':', 1)
            fields[key.lower()] = value.strip()
        return reader.read(int(fields.get('content-length', 0)))
    info = plistlib.loads(request('/info', method='GET'))
    assert info['name'] == state['name']
    assert info['model'] == 'AppleTV3,2'
    print('PASS: live /info reports the configured receiver')
    client_ed = ed25519.Ed25519PrivateKey.generate()
    ed_public = client_ed.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    server_ed = request('/pair-setup', ed_public)
    assert len(server_ed) == 32
    print('PASS: local Ed25519 pairing identity')
    client_x = x25519.X25519PrivateKey.generate()
    x_public = client_x.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    response = request('/pair-verify', b'\x01\x00\x00\x00' + x_public + ed_public)
    assert len(response) == 96
    server_x = response[:32]
    secret = client_x.exchange(x25519.X25519PublicKey.from_public_bytes(server_x))
    key = hashlib.sha512(b'Pair-Verify-AES-Key' + secret).digest()[:16]
    iv = hashlib.sha512(b'Pair-Verify-AES-IV' + secret).digest()[:16]
    crypt = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()
    signature = crypt.update(response[32:])
    ed25519.Ed25519PublicKey.from_public_bytes(server_ed).verify(signature, server_x + x_public)
    signature = client_ed.sign(x_public + server_x)
    request('/pair-verify', b'\x00\x00\x00\x00' + crypt.update(signature))
    print('PASS: X25519 shared secret, server signature and encrypted client verification')
    fp = bytearray(16)
    fp[:4] = b'FPLY'
    fp[4:8] = bytes([3, 1, 1, 0])
    fp[14] = 0
    reply = request('/fp-setup', bytes(fp))
    assert len(reply) == 142 and reply.startswith(b'FPLY')
    print('PASS: FairPlay setup response is computed locally')
    reader.close()
    sock.close()
    print('All protocol smoke checks passed.')

if __name__ == '__main__':
    main()
