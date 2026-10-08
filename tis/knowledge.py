"""Company knowledge retrieval (the "R" in RAG).

A self-contained BM25 index over the SOP folder, with document-level access
control. Production would swap this for OpenSearch hybrid search or Qdrant
plus a local embedding model; the `search()` contract stays the same.
"""
import math
import os
import re
from collections import Counter
from dataclasses import dataclass

KNOWLEDGE_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "knowledge", "sops")
ROLE_ACCESS = {"viewer": {"all"}, "coordinator": {"all"}, "supervisor": {"all", "supervisor"}}
STOP = set("a an the of to in on for and or is are be if at by with what when how do does should i we our "
           "my it this that from as not can must any".split())


def tokens(text):
    out = []
    for w in re.findall(r"[a-z0-9]+", text.lower()):
        if w in STOP:
            continue
        for suffix in ("ing", "ed", "es", "s"):      # crude stemming is enough here
            if len(w) > 4 and w.endswith(suffix):
                w = w[: -len(suffix)]
                break
        out.append(w)
    return out


@dataclass
class Chunk:
    doc_id: str
    title: str
    section: str
    text: str
    access: str


def load(folder=KNOWLEDGE_DIR):
    chunks = []
    for name in sorted(os.listdir(folder)):
        if not name.endswith(".md"):
            continue
        with open(os.path.join(folder, name), encoding="utf-8") as f:
            raw = f.read()
        meta, body = {}, raw
        if raw.startswith("---"):
            head, body = raw[3:].split("---", 1)
            meta = dict(line.split(":", 1) for line in head.strip().splitlines())
            meta = {k.strip(): v.strip() for k, v in meta.items()}
        for part in re.split(r"^## ", body, flags=re.M):
            part = part.strip()
            if not part:
                continue
            section, _, text = part.partition("\n")
            # title and tags are indexed with every section so short sections stay findable
            chunks.append(Chunk(meta.get("id", name), meta.get("title", name), section.strip(),
                                text.strip() + "\n" + meta.get("title", "") + " " + meta.get("tags", ""),
                                meta.get("access", "all")))
    return chunks


class KnowledgeBase:
    def __init__(self, chunks=None, k1=1.4, b=0.75):
        self.chunks = chunks if chunks is not None else load()
        self.k1, self.b = k1, b
        self.docs = [Counter(tokens(c.section + " " + c.text)) for c in self.chunks]
        self.avgdl = sum(sum(d.values()) for d in self.docs) / max(1, len(self.docs))
        df = Counter(t for d in self.docs for t in d)
        n = len(self.docs)
        self.idf = {t: math.log(1 + (n - f + 0.5) / (f + 0.5)) for t, f in df.items()}

    def search(self, query, role="coordinator", k=3, min_score=1.0):
        allowed = ROLE_ACCESS.get(role, {"all"})
        q = tokens(query)
        scored = []
        for c, d in zip(self.chunks, self.docs):
            if c.access not in allowed:
                continue
            dl = sum(d.values())
            s = sum(self.idf.get(t, 0) * d[t] * (self.k1 + 1) /
                    (d[t] + self.k1 * (1 - self.b + self.b * dl / self.avgdl)) for t in q if t in d)
            if s >= min_score:
                scored.append((s, c))
        scored.sort(key=lambda x: -x[0])
        return [(round(s, 2), c) for s, c in scored[:k]]
