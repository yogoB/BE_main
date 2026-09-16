#!/usr/bin/env python3
"""여러 표를 한 CSV 파일에 담는 섹션 형식. 운영 합본(db/seed/catalog_combined.csv)과 같은 규칙이다.

    #@ <dataset>      섹션 경계. 다음 비주석 줄이 그 섹션의 헤더고, 이후가 데이터 행이다.
    # ...             주석(왕복 시 보존하지 않는다)

Java 쪽 구현은 `CombinedCatalogCsv` 다. 형식을 바꾸면 양쪽을 함께 고친다.
"""
import csv
import io

MARKER = "#@ "


def split(text):
    """{데이터셋: CSV 원문} 으로 나눈다. 섹션 밖 데이터 행은 오류다(조용히 버리지 않는다)."""
    sections, current, rows = {}, None, []
    for raw in text.split("\n"):
        line = raw[:-1] if raw.endswith("\r") else raw
        if line.startswith(MARKER):
            if current is not None:
                sections[current] = "\n".join(rows)
            current = line[len(MARKER):].strip()
            if current in sections:
                raise ValueError(f"중복 섹션: {current}")
            rows = []
        elif line.strip() and not line.startswith("#"):
            if current is None:
                raise ValueError("섹션 밖의 데이터 행이 있습니다")
            rows.append(line)
    if current is not None:
        sections[current] = "\n".join(rows)
    return sections


def read(path, dataset):
    """섹션 하나를 dict 행 목록으로 읽는다."""
    with open(path, encoding="utf-8-sig") as handle:
        sections = split(handle.read())
    if dataset not in sections:
        raise ValueError(f"'{dataset}' 섹션이 없습니다: {path} (있는 것: {', '.join(sections)})")
    return list(csv.DictReader(io.StringIO(sections[dataset])))


def write(path, datasets, header_comment=""):
    """{데이터셋: CSV 원문} 을 한 파일로 쓴다. 줄바꿈은 LF."""
    parts = [header_comment.rstrip("\n") + "\n"] if header_comment else []
    for name, body in datasets.items():
        parts.append(f"{MARKER}{name}\n{body.rstrip(chr(10))}\n")
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(parts))
