#!/usr/bin/env python3
"""Check the running local receiver with macOS's actual Bonjour browser."""
import json
import shutil
import subprocess
import urllib.request


def observe(*arguments):
    process = subprocess.Popen(['dns-sd', *arguments], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True)
    try:
        output, _ = process.communicate(timeout=5)
    except subprocess.TimeoutExpired:
        process.terminate()
        output, _ = process.communicate(timeout=3)
    return output


def main():
    if not shutil.which('dns-sd'):
        raise SystemExit('This optional discovery check requires macOS dns-sd.')
    state = json.load(urllib.request.urlopen('http://127.0.0.1:2244/api/state', timeout=5))
    assert state['status'] == 'ready', state
    for service in ('_airplay._tcp', '_raop._tcp'):
        output = observe('-B', service, 'local.')
        assert state['name'] in output, f'{service}: receiver missing from Bonjour browse'
        print('PASS:', service, 'advertises', state['name'])
    output = observe('-L', state['name'], '_airplay._tcp', 'local.')
    assert ':' + str(state['port']) in output, 'Bonjour advertises a stale receiver port'
    assert 'features=' in output and 'pk=' in output, 'Missing AirPlay capability/identity records'
    print('PASS: service resolves to the active receiver port with capability and identity records')


if __name__ == '__main__':
    main()
