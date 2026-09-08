#!/usr/bin/env python3
"""Sync the repo worklog, Git history, and graphify exports to an Obsidian project folder."""

import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from datetime import datetime

ROOT = Path(__file__).resolve().parents[1]
OWNER = '<!-- yogobi:generated -->'
START = '<!-- yogobi:worklog:start -->'
END = '<!-- yogobi:worklog:end -->'


def run(*args, capture=True):
    result = subprocess.run(args, cwd=ROOT, check=True, text=True,
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else ''


def config(key):
    result = subprocess.run(['git', 'config', '--local', '--get', key], cwd=ROOT,
                            text=True, stdout=subprocess.PIPE, check=False)
    return result.stdout.strip()


def write_atomic(path, content):
    if path.exists() and path.read_text(encoding='utf-8') == content:
        return
    with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent,
                                     prefix='.yogobi-', delete=False) as output:
        temporary = Path(output.name)
        try:
            output.write(content)
            output.flush()
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)


def worklog_block(existing, body):
    block = START + '\n' + body.strip() + '\n' + END
    if START in existing or END in existing:
        if existing.count(START) != 1 or existing.count(END) != 1:
            raise ValueError('개발 기록의 자동 관리 구간이 손상됐습니다. 원문을 보존하고 중단합니다.')
        before, tail = existing.split(START)
        _, after = tail.split(END)
        return before + block + after
    first_entry = re.search(r'^## ', existing, re.MULTILINE)
    position = first_entry.start() if first_entry else len(existing)
    return existing[:position].rstrip() + '\n\n' + block + '\n\n' + existing[position:]


def embed_graph(graph_dir, project_dir):
    vault_root = next((p for p in (project_dir, *project_dir.parents)
                       if (p / '.obsidian').is_dir()), project_dir)
    prefix = graph_dir.relative_to(vault_root).as_posix()
    manifest = json.loads((graph_dir / '.graphify_obsidian_manifest.json').read_text())
    notes = [graph_dir / name for name in manifest['files'] if name.endswith('.md')]
    stems = {p.stem for p in notes}

    def link(match):
        target, separator, alias = match.group(1).partition('|')
        return f'[[{prefix}/{target}|{alias if separator else target}]]' if target in stems else match.group(0)

    for note in notes:
        write_atomic(note, re.sub(r'\[\[([^\]]+)\]\]', link, note.read_text(encoding='utf-8')))
    canvas_path = graph_dir / 'graph.canvas'
    canvas = json.loads(canvas_path.read_text(encoding='utf-8'))
    for node in canvas['nodes']:
        if node['type'] == 'file':
            node['file'] = prefix + '/' + node['file']
    write_atomic(canvas_path, json.dumps(canvas, ensure_ascii=False, indent=2))
    return prefix


def install(vault_dir, python):
    vault = Path(vault_dir).expanduser().resolve(strict=True)
    if not vault.is_dir():
        raise ValueError('볼트의 프로젝트 디렉터리를 지정하세요.')
    interpreter = Path(python).expanduser().absolute()
    run(str(interpreter), '-c', 'import graphify')
    current = config('core.hooksPath')
    hooks_dir = Path(run('git', 'rev-parse', '--git-path', 'hooks'))
    if not hooks_dir.is_absolute():
        hooks_dir = ROOT / hooks_dir
    active = [p for p in hooks_dir.glob('*') if not p.name.endswith('.sample') and os.access(p, os.X_OK)]
    if (current and current != '.githooks') or (not current and active):
        raise ValueError('기존 Git 훅이 있습니다. 덮어쓰지 않습니다. 기존 훅에서 sync를 호출하세요.')
    run('git', 'config', '--local', 'yogobi.vaultDir', str(vault))
    run('git', 'config', '--local', 'yogobi.graphifyPython', str(interpreter))
    run('git', 'config', '--local', 'core.hooksPath', '.githooks')
    print('설치 완료. python3 scripts/knowledge.py sync 로 최초 동기화하세요.')


def sync():
    vault_setting = config('yogobi.vaultDir')
    python = config('yogobi.graphifyPython')
    if not vault_setting or not python:
        raise ValueError('먼저 install --vault-dir ... --python ... 을 실행하세요.')
    vault = Path(vault_setting).resolve(strict=True)
    if vault == ROOT or ROOT in vault.parents:
        raise ValueError('Obsidian 출력은 레포 밖에 두세요. 그래프가 자기 출력을 읽을 수 있습니다.')
    out = ROOT / 'graphify-out'
    out.mkdir(exist_ok=True)
    with (out / 'knowledge.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        run(python, '-m', 'graphify', 'update', '.', capture=False)
        graph = json.loads((out / 'graph.json').read_text(encoding='utf-8'))
        if not graph.get('nodes'):
            raise ValueError('빈 그래프는 내보내지 않습니다.')
        pending = json.loads(run(python, '-c', '''
import json
from pathlib import Path
from graphify.detect import detect_incremental
d = detect_incremental(Path.cwd(), kind="semantic")
print(json.dumps(sorted(str(Path(f).relative_to(Path.cwd()))
    for kind, files in d["new_files"].items() if kind != "code" for f in files)))
'''))
        graph_dir = vault / 'Graphify'
        run(python, '-m', 'graphify', 'export', 'obsidian', '--dir', str(graph_dir), capture=False)
        graph_prefix = embed_graph(graph_dir, vault)
        run(python, '-m', 'graphify', 'export', 'html', capture=False)

        devlog = vault / 'yogoB_BE - 개발 기록.md'
        existing = devlog.read_text(encoding='utf-8') if devlog.exists() else '# yogoB_BE - 개발 기록\n'
        updated = worklog_block(existing, (ROOT / 'docs/worklog.md').read_text(encoding='utf-8'))
        status = run('git', '-c', 'core.quotePath=false', 'status', '--short', '--untracked-files=all')
        # ponytail: regenerate the full Git log; split by month if this note becomes unwieldy.
        history = run('git', '-c', 'core.quotePath=false', 'log', '--all', '--date=iso-strict',
                      '--format=%n### %ad · %h%n%s%n', '--name-status')
        branch = run('git', 'rev-parse', '--abbrev-ref', 'HEAD')
        head = run('git', 'rev-parse', '--short', 'HEAD')
        state = (ROOT / 'docs/state.md').read_text(encoding='utf-8')
        next_match = re.search(r'^## 다음 할 일\n(.*?)(?=^## |\Z)', state, re.MULTILINE | re.DOTALL)
        next_work = next_match.group(1).strip() if next_match else 'docs/state.md를 확인하세요.'
        pending_text = ('의미 분석 갱신 필요: ' + ', '.join(f'`{p}`' for p in pending)
                        if pending else '문서 의미 분석: 최신')
        communities = sorted(graph_dir.glob('_COMMUNITY_*.md'))
        links = '\n'.join(f'- [[{graph_prefix}/{p.stem}|{p.stem}]]' for p in communities)
        body = f'''---
tags: [yogobi, tracking, graphify]
---
{OWNER}

# yogoB_BE - 자동 추적

동기화: {datetime.now().astimezone().isoformat(timespec='seconds')} · `{branch}` / `{head}`
그래프는 **현재 작업 트리** 기준입니다. 아래 미커밋 변경은 커밋된 이력이 아닙니다.
{pending_text}
문서가 바뀌면 에이전트에서 `/graphify --update` 후 `python3 scripts/knowledge.py sync`를 실행합니다.

- [[yogoB_BE - 개발 기록]] · [[yogoB_BE - 변경 이력]]
- [[{graph_prefix}/graph.canvas|지식 그래프 Canvas]]
- [인터랙티브 HTML]({(out / 'graph.html').as_uri()})
- [현재 상태 원본]({(ROOT / 'docs/state.md').as_uri()})
- 노드 {len(graph['nodes'])}개 · 관계 {len(graph.get('links', graph.get('edges', [])))}개

Canvas는 주요 관계 200개까지 표시합니다. 전체 관계는 HTML과 노드 노트에서 확인하세요.

## 그래프 탐색

{links}

## 다음 작업

{next_work}

## 미커밋 변경

```text
{status or '(없음)'}
```

## Git 변경 이력

```text
{history}
```
'''
        note = vault / 'yogoB_BE - 자동 추적.md'
        if note.exists() and OWNER not in note.read_text(encoding='utf-8'):
            raise ValueError(f'사용자 노트는 덮어쓰지 않습니다: {note}')
        write_atomic(devlog, updated)
        write_atomic(note, body)
        print(f'기록 동기화 완료: {vault} (문서 의미 분석 대기 {len(pending)}개)')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    setup = commands.add_parser('install')
    setup.add_argument('--vault-dir', required=True)
    setup.add_argument('--python', required=True, help='graphify가 설치된 Python 실행 파일')
    commands.add_parser('sync')
    args = parser.parse_args()
    if args.command == 'install':
        install(args.vault_dir, args.python)
    else:
        sync()


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f'[yogobi] {error}', file=sys.stderr)
        sys.exit(1)
