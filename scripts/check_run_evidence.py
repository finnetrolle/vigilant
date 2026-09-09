"""File integrity primitives for check-run; this module never launches checks."""

import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import stat
import sys


REQUEST_FILE = 'request.json'
RESULT_FILE = 'result.json'
SEALED_FILES = (REQUEST_FILE, RESULT_FILE, 'inputs-before.json',
                'inputs-after.json', 'artifacts.json')


def digest_file(path):
    """Hash a regular file without following a last-component symlink."""
    with open_regular(path) as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode):
            raise ValueError('expected a regular file')
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        after = os.fstat(stream.fileno())
        if (before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (after.st_size, after.st_mtime_ns, after.st_ctime_ns):
            raise ValueError('file changed while hashing')
        return {'sha256': digest, 'mode': stat.S_IMODE(after.st_mode)}


def regular_path(root, relative):
    """Reject symlink traversal at every component of an owned relative path."""
    path = root
    for part in Path(relative).parts:
        path /= part
        if path.is_symlink():
            raise ValueError('symlink in evidence path')
    return path


def open_regular(path, mode='rb'):
    """Return a caller-owned regular-file stream without link traversal or FIFO waits."""
    regular_path(path.parent, path.name)
    stream = os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), mode)
    try:
        if stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            return stream
        raise ValueError('evidence path is not a regular file')
    except BaseException:
        stream.close()
        raise


def read_json(path):
    """Read an evidence document through the common safe file boundary."""
    with open_regular(path, 'r') as stream:
        return json.load(stream)


def directory_artifact(path):
    """Hash a complete nonempty artifact tree, rejecting links and special files."""
    if not path.is_dir():
        raise ValueError('declared artifact directory is missing')
    value = {}
    for parent, directories, files in os.walk(path, followlinks=False):
        for name in sorted(directories + files):
            child = Path(parent) / name
            if child.is_symlink():
                raise ValueError('symlink in artifact directory')
            key = child.relative_to(path).as_posix()
            value[key] = {'type': 'directory'} if child.is_dir() else digest_file(child)
    if not any('sha256' in item for item in value.values()):
        raise ValueError('declared artifact directory has no files')
    return value


def artifact_snapshot(root, declarations):
    """Capture file or complete nonempty directory artifacts with literal paths."""
    entries = []
    for declaration in declarations:
        kind, relative = declaration.split(':', 1)
        path = regular_path(root, relative)
        value = digest_file(path) if kind == 'file' else directory_artifact(path)
        entries.append({'type': kind, 'path': relative, 'digest': value})
    return entries if entries else 'none'


def directory_tool_identity(resolved):
    """Track tool-tree bytes and file symlink referents without directory symlinks."""
    files = {}
    for child in sorted(resolved.rglob('*')):
        if not child.is_symlink() and not child.is_file():
            continue
        target = child.resolve(strict=True)
        if not target.is_file():
            raise ValueError('declare toolchain directories without directory symlinks')
        files[child.relative_to(resolved).as_posix()] = {'resolved': str(target), **digest_file(target)}
    return {'files': files}


def tool_identity(path):
    """Retain absence, resolved location and current content of one declared tool."""
    if not path.exists() and not path.is_symlink():
        return {'missing': True}
    resolved = path.resolve(strict=True)
    identity = directory_tool_identity(resolved) if resolved.is_dir() else digest_file(resolved)
    return {'resolved': str(resolved), **identity}


def tool_snapshot(root, command, tools):
    """Identify the runner, interpreter, OS, executable and declared tool files."""
    executable = shutil.which(command[0], path=os.environ.get('PATH'))
    if '/' in command[0]:
        path = root / command[0]
        executable = str(path) if path.exists() else None
    selected = [str(Path(__file__)), str(Path(__file__).with_name('check-run')),
                sys.executable, shutil.which('git')]
    # An absent command is an execution failure, not an invalid CLI contract.
    if executable:
        selected.append(executable)
    selected.extend(tools)
    identities = {}
    for name in sorted(set(selected)):
        path = Path(name)
        if not path.is_absolute():
            path = root / path
        identities[str(path)] = tool_identity(path)
    return {'platform': platform.platform(), 'python': sys.version,
            'executable': executable, 'files': identities}


def seal(path, publish):
    """Publish the terminal manifest last, binding every evidence document."""
    publish(path / 'seal.json', {name: digest_file(path / name) for name in SEALED_FILES})


def verify_seal(path):
    """Reject partial, legacy or modified terminal records before interpreting PASS."""
    manifest = read_json(path / 'seal.json')
    if set(manifest) != set(SEALED_FILES):
        raise ValueError('incomplete evidence manifest')
    for name in SEALED_FILES:
        if digest_file(path / name) != manifest[name]:
            raise ValueError('modified evidence document')
