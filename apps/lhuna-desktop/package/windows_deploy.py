#!/usr/bin/env python3
"""
Windows package of cross compiled Lhuna: executable, Qt libraries and plugins, C++ runtime of llvm-mingw.

windeployqt is a Windows program, so deployment is done here: required plugins are copied explicitly and all the
DLL imports are resolved recursively by objdump, so missing library fails the build instead of the user's start.

Usage: windows_deploy.py <exe> <output dir> --qt <Qt for Windows dir> --toolchain <llvm-mingw dir>
"""

import argparse
import os
import re
import shutil
import subprocess
import sys

# platforms: native one and offscreen for headless checks, styles: native look of file dialogs
PLUGINS = [
    'platforms/qwindows.dll',
    'platforms/qoffscreen.dll',
    'styles/qmodernwindowsstyle.dll',
    'imageformats/qico.dll',
]

# Libraries of the system (including UCRT api sets), never shipped
SYSTEM = re.compile(
    r'^(api-ms-win-.*|ext-ms-.*|kernel32|user32|gdi32|gdiplus|advapi32|shell32|shlwapi|ole32|oleaut32|comdlg32|'
    r'comctl32|winmm|ws2_32|mpr|userenv|netapi32|version|authz|dwmapi|uxtheme|imm32|setupapi|cfgmgr32|d3d9|d3d11|'
    r'd3d12|dxgi|dcomp|dwrite|d2d1|opengl32|winspool\.drv|crypt32|bcrypt|ncrypt|secur32|iphlpapi|dnsapi|wtsapi32|'
    r'shcore|rpcrt4|ntdll|msvcrt|ucrtbase|dsound|propsys|wintrust|powrprof|hid|dbghelp|synchronization|'
    r'mf|mfplat|mfreadwrite|wevtapi|userenv|uiautomationcore|runtimeobject)(\.dll)?$', re.IGNORECASE)


def imports(objdump, path):
    out = subprocess.run([objdump, '-p', path], check=True, capture_output=True, text=True).stdout
    return re.findall(r'DLL Name:\s*(\S+)', out)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('exe')
    parser.add_argument('output')
    parser.add_argument('--qt', required=True)
    parser.add_argument('--toolchain', required=True)
    parser.add_argument('--triple', default='x86_64-w64-mingw32')
    args = parser.parse_args()

    objdump = os.path.join(args.toolchain, 'bin', f'{args.triple}-objdump')
    search = [os.path.join(args.qt, 'bin'), os.path.join(args.toolchain, args.triple, 'bin')]
    os.makedirs(args.output, exist_ok=True)
    pending = [shutil.copy2(args.exe, args.output)]
    for plugin in PLUGINS:
        source = os.path.join(args.qt, 'plugins', plugin)
        if not os.path.exists(source):
            sys.exit(f'Missing Qt plugin {source}')
        target = os.path.join(args.output, plugin)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        pending.append(shutil.copy2(source, target))

    deployed = {}
    system = set()
    while pending:
        binary = pending.pop()
        for dll in imports(objdump, binary):
            key = dll.lower()
            if key in deployed or key in system:
                continue
            location = next((os.path.join(d, f) for d in search if os.path.isdir(d)
                             for f in os.listdir(d) if f.lower() == key), None)
            if location:
                deployed[key] = shutil.copy2(location, args.output)
                pending.append(deployed[key])
            elif SYSTEM.match(key):
                system.add(key)
            else:
                sys.exit(f'Unresolved dependency {dll} of {binary}')
    print('Deployed:', ', '.join(sorted(os.path.basename(p) for p in deployed.values())))
    print('System:', ', '.join(sorted(system)))


if __name__ == '__main__':
    main()
