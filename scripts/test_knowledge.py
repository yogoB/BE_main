#!/usr/bin/env python3
"""One real Git + graphify + temporary-vault integration check."""

import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('knowledge', ROOT / 'scripts/knowledge.py')
knowledge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(knowledge)


def check():
    graphify_python = (ROOT / 'graphify-out/.graphify_python').read_text().strip()
    with tempfile.TemporaryDirectory(prefix='yogobi knowledge ') as directory:
        base = Path(directory)
        repo, vault_root = base / 'repo', base / '간사 볼트'
        vault = vault_root / '개발' / 'yogoB_BE'
        repo.mkdir()
        vault.mkdir(parents=True)
        (vault_root / '.obsidian').mkdir()
        shutil.copytree(ROOT / 'scripts', repo / 'scripts', ignore=shutil.ignore_patterns('__pycache__'))
        shutil.copytree(ROOT / '.githooks', repo / '.githooks')
        (repo / 'docs').mkdir()
        (repo / '.gitignore').write_text('graphify-out/\n__pycache__/\n')
        (repo / 'docs/state.md').write_text('## 다음 할 일\n1. 가격 테스트\n', encoding='utf-8')
        (repo / 'docs/worklog.md').write_text('## 2026-09-08\n검증한 개발 기록\n', encoding='utf-8')
        (repo / 'example.py').write_text('def first():\n    return 1\n')
        devlog = vault / 'yogoB_BE - 개발 기록.md'
        devlog.write_text('# 개발 기록\n\n사용자 서문\n\n## 2026-09-07\n기존 수기 기록\n', encoding='utf-8')

        def run(*args, success=True):
            result = subprocess.run(args, cwd=repo, text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT)
            if success:
                assert result.returncode == 0, result.stdout
            return result

        run('git', 'init', '-b', 'main')
        run('git', 'config', 'user.name', 'Knowledge Test')
        run('git', 'config', 'user.email', 'knowledge-test@example.invalid')
        hook = repo / '.git/hooks/post-commit'
        hook.write_text('#!/bin/sh\nexit 0\n')
        hook.chmod(0o755)
        install = (sys.executable, 'scripts/knowledge.py', 'install', '--vault-dir', str(vault),
                   '--python', graphify_python)
        assert run(*install, success=False).returncode != 0
        assert hook.read_text() == '#!/bin/sh\nexit 0\n'
        hook.unlink()
        run(*install)
        run('git', 'add', '.')
        committed = run('git', 'commit', '-m', '[human] feat: initial sample')
        assert '기록 동기화 완료' in committed.stdout, committed.stdout
        auto = vault / 'yogoB_BE - 자동 추적.md'
        assert auto.is_file(), committed.stdout
        assert '[human] feat: initial sample' in auto.read_text(encoding='utf-8')
        assert (vault / 'Graphify/graph.canvas').is_file()
        canvas = json.loads((vault / 'Graphify/graph.canvas').read_text(encoding='utf-8'))
        for node in canvas['nodes']:
            if node['type'] == 'file':
                assert (vault_root / node['file']).is_file(), node['file']
        assert '[[개발/yogoB_BE/Graphify/graph.canvas|' in auto.read_text(encoding='utf-8')
        text = devlog.read_text(encoding='utf-8')
        assert '사용자 서문' in text and '기존 수기 기록' in text
        assert text.index('2026-09-08') < text.index('2026-09-07')
        run(sys.executable, 'scripts/knowledge.py', 'sync')
        assert devlog.read_text(encoding='utf-8') == text

        (repo / 'example.py').write_text('def second():\n    return 2\n')
        run('git', 'add', 'example.py')
        committed = run('git', 'commit', '-m', '[human] refactor: rename sample')
        assert '기록 동기화 완료' in committed.stdout, committed.stdout
        run('git', 'checkout', '-b', 'check-branch')
        latest = auto.read_text(encoding='utf-8')
        assert 'check-branch' in latest and 'rename sample' in latest
        (repo / 'docs/state.md').write_text('## 다음 할 일\n1. 변경된 다음 작업\n', encoding='utf-8')
        run(sys.executable, 'scripts/knowledge.py', 'sync')
        latest = auto.read_text(encoding='utf-8')
        assert '의미 분석 갱신 필요' in latest and 'docs/state.md' in latest
        assert '변경된 다음 작업' in latest
        assert devlog.read_text(encoding='utf-8').count(knowledge.START) == 1

        auto.write_text('사용자가 직접 만든 노트', encoding='utf-8')
        assert run(sys.executable, 'scripts/knowledge.py', 'sync', success=False).returncode != 0
        assert auto.read_text(encoding='utf-8') == '사용자가 직접 만든 노트'
    print('PASS: commit/checkout hooks, native graph export, history, pending docs, and note preservation')


if __name__ == '__main__':
    check()
