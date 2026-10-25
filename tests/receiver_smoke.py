#!/usr/bin/env python3
"""Exercise PIN pairing and disconnect recovery against an isolated real receiver.

Requires cryptography and the built desktop executable. No physical sender or
audio/video output is involved; Apple-to-TV playback still needs a manual test.
"""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import secrets
import socket
import struct
import subprocess
import tempfile
import threading

from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
import plistlib

ROOT = Path(__file__).resolve().parents[1]
N = int('AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4'
        'A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF60'
        '95179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF'
        '747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B907'
        '8717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB37861'
        '60279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DB'
        'FBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73', 16)


def num(n, length=None):
    return n.to_bytes(length if length is not None else (n.bit_length() + 7) // 8, 'big')


def sha(*data):
    return hashlib.sha1(b''.join(data)).digest()


class Connection:
    def __init__(self, port):
        self.sock = socket.create_connection(('127.0.0.1', port), timeout=5)
        self.reader = self.sock.makefile('rb')
        self.sequence = 0

    def request(self, path, body=b'', method='POST', expected=200):
        self.sequence += 1
        kind = 'application/octet-stream'
        if isinstance(body, dict):
            kind = 'application/x-apple-binary-plist'
            body = plistlib.dumps(body, fmt=plistlib.FMT_BINARY)
        content_type = f'Content-Type: {kind}\r\n' if body else ''
        header = (f'{method} {path} RTSP/1.0\r\nCSeq: {self.sequence}\r\n'
                  f'User-Agent: AirPlay/745.13.4\r\n{content_type}'
                  f'Content-Length: {len(body)}\r\n\r\n')
        self.sock.sendall(header.encode() + body)
        status = self.reader.readline().decode().strip()
        assert len(status.split()) >= 2, (path, status)
        assert int(status.split()[1]) == expected, (path, status, expected)
        fields = {}
        while True:
            line = self.reader.readline()
            if line in (b'\r\n', b'\n', b''):
                break
            key, value = line.decode().split(':', 1)
            fields[key.lower()] = value.strip()
        return self.reader.read(int(fields.get('content-length', 0)))

    def close(self):
        self.reader.close()
        self.sock.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


class Receiver:
    def __init__(self, binary, pin=True):
        self.temp = tempfile.TemporaryDirectory(prefix='22cast-test-')
        self.events = queue.Queue()
        args = [str(binary), '--headless', '--name', '22Cast protocol check',
                '--id', '02:22:CA:57:99:01', '--key', self.temp.name + '/identity.pem']
        if pin:
            args.append('--pin')
        self.process = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.PIPE, text=True)
        self.errors = []
        threading.Thread(target=self.read_events, daemon=True).start()
        threading.Thread(target=self.read_errors, daemon=True).start()
        self.port = int(self.event('ready'))

    def read_events(self):
        for line in self.process.stdout:
            try:
                self.events.put(json.loads(line))
            except ValueError:
                pass
        self.events.put(dict(event='exited', value=str(self.process.wait())))

    def read_errors(self):
        self.errors.extend(self.process.stderr)

    def event(self, kind):
        while True:
            message = self.events.get(timeout=8)
            if message['event'] == kind:
                return message.get('value', '')
            assert message['event'] not in ('error', 'exited'), (message, self.errors[-5:])

    def close(self):
        if self.process.poll() is None:
            self.process.communicate('quit\n', timeout=8)
        self.temp.cleanup()


def verify_pair(connection, identity, receiver_key):
    client = x25519.X25519PrivateKey.generate()
    public = client.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    ed_public = identity.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
    response = connection.request('/pair-verify', b'\1\0\0\0' + public + ed_public)
    assert len(response) == 96, len(response)
    shared = client.exchange(x25519.X25519PublicKey.from_public_bytes(response[:32]))
    key = hashlib.sha512(b'Pair-Verify-AES-Key' + shared).digest()[:16]
    iv = hashlib.sha512(b'Pair-Verify-AES-IV' + shared).digest()[:16]
    cipher = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()
    signature = cipher.update(response[32:])
    ed25519.Ed25519PublicKey.from_public_bytes(receiver_key).verify(signature, response[:32] + public)
    connection.request('/pair-verify', b'\0\0\0\0' + cipher.update(identity.sign(public + response[:32])))


def pair_pin(receiver, wrong=False, bad_tag=False):
    with Connection(receiver.port) as connection:
        connection.request('/pair-pin-start')
        pin = receiver.event('pin')
        assert len(pin) == 4 and pin.isdigit(), pin
        if wrong:
            pin = f'{(int(pin) + 1) % 10000:04d}'
        user = '22:CA:57:00:00:01'
        challenge = plistlib.loads(connection.request('/pair-setup-pin', dict(method='pin', user=user)))
        salt = num(int.from_bytes(challenge['salt'], 'big'))
        b = int.from_bytes(challenge['pk'], 'big')
        assert b % N != 0
        private = secrets.randbits(256)
        a = pow(2, private, N)
        k = int.from_bytes(sha(num(N, 256), num(2, 256)), 'big')
        u = int.from_bytes(sha(num(a, 256), num(b, 256)), 'big')
        x = int.from_bytes(sha(salt, sha(user.encode() + b':' + pin.encode())), 'big')
        shared = pow((b - k * pow(2, x, N)) % N, private + u * x, N)
        key = sha(num(shared), b'\0\0\0\0') + sha(num(shared), b'\0\0\0\1')
        xor = bytes(x ^ y for x, y in zip(sha(num(N)), sha(num(2))))
        proof = sha(xor, sha(user.encode()), salt, num(a), num(b), key)
        reply = connection.request('/pair-setup-pin', dict(pk=num(a), proof=proof),
                                   expected=470 if wrong else 200)
        if wrong:
            return
        assert plistlib.loads(reply)['proof'] == sha(num(a), proof, key)
        identity = ed25519.Ed25519PrivateKey.generate()
        public = identity.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
        aes_key = hashlib.sha512(b'Pair-Setup-AES-Key' + key).digest()[:16]
        iv = bytearray(hashlib.sha512(b'Pair-Setup-AES-IV' + key).digest()[:16])
        iv[-1] = (iv[-1] + 1) % 256
        encrypted = AESGCM(aes_key).encrypt(bytes(iv), public, None)
        tag = encrypted[32:]
        if bad_tag:
            tag = bytes([tag[0] ^ 1]) + tag[1:]
        reply = connection.request('/pair-setup-pin', dict(epk=encrypted[:32], authTag=tag), expected=470 if bad_tag else 200)
        if bad_tag:
            return
        response = plistlib.loads(reply)
        iv[-1] = (iv[-1] + 1) % 256
        receiver_key = AESGCM(aes_key).decrypt(bytes(iv), response['epk'] + response['authTag'], None)
        assert len(receiver_key) == 32
        verify_pair(connection, identity, receiver_key)


def run_checks(receiver):
    try:
        with Connection(receiver.port) as connection:
            connection.request('/pair-setup', secrets.token_bytes(32), expected=470)
            connection.request('/pair-verify', b'\1\0\0\0' + secrets.token_bytes(64), expected=470)
            connection.request('/stream', {}, method='SETUP', expected=470)
        print('PASS: PIN-required mode rejects non-PIN setup')
        pair_pin(receiver)
        print('PASS: complete SRP PIN setup, mutual AES-GCM identity authentication and pair verification')
        pair_pin(receiver, wrong=True)
        pair_pin(receiver, bad_tag=True)
        pair_pin(receiver)
        print('PASS: wrong PIN and tampered authentication tag rejected; fresh pairing still succeeds')
        # Out-of-order or truncated pairing packets must fail without stopping reception.
        for body in (dict(pk=b'\1', proof=b'\0' * 20), dict(epk=b'\0' * 32, authTag=b'\0' * 16),
                     dict(epk=b'', authTag=b''), dict(pk=b'\1', proof=b'')):
            with Connection(receiver.port) as connection:
                connection.request('/pair-setup-pin', body, expected=470)
        print('PASS: malformed and out-of-order PIN messages rejected safely')
        # Reset TCP before reading each response, then check the same process again.
        if hasattr(receiver, 'reset_connections'):
            # Run socket resets on-device; ADB forwarding does not preserve RST.
            receiver.reset_connections()
        else:
            for _ in range(64):
                sock = socket.create_connection(('127.0.0.1', receiver.port), timeout=5)
                sock.sendall(b'GET /info RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n')
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0))
                sock.close()
        with Connection(receiver.port) as connection:
            info = plistlib.loads(connection.request('/info', method='GET'))
            assert info['name'] == getattr(receiver, 'name', '22Cast protocol check')
        pair_pin(receiver)
        assert receiver.process.poll() is None
        print('PASS: abrupt socket resets preserve discovery, pairing and process availability')
    finally:
        receiver.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', type=Path, default=ROOT / 'native/build/desktop/cast22')
    args = parser.parse_args()
    run_checks(Receiver(args.binary))


if __name__ == '__main__':
    main()
