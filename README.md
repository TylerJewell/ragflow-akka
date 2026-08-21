# ragflow-akka

Splits a document into chunks by a configurable rule, tracks each chunk-producing
task from queued to done or failed, and reuses a task's chunks instead of redoing
the work when nothing about the document or its chunking configuration has changed.

A port of [infiniflow/ragflow](https://github.com/infiniflow/ragflow) onto **Akka**,
built with **Akka Specify**.

---

## Where it came from

infiniflow/ragflow is a retrieval-augmented-generation platform: it takes in
documents, breaks them into pieces a search index can work with, and answers
questions by finding and citing the relevant pieces. It was ported to derive a
specification format precise enough to regenerate a system on a different stack —
the port is the vehicle, the specification is the deliverable.

This port takes one slice of it: parsing plain text, Markdown, and PDF documents,
splitting them into chunks, and the multi-step process — queue, split into tasks,
chunk, embed, index, track progress — a document goes through to get there. It does
not run the platform's search, its chat agents, its OCR and page-layout models, or
its nine other chunking strategies.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `ragflow-port/`.

---

## infiniflow/ragflow → this port

📉 591 Python lines (the ported slice) → **1,176 Java lines**<br>
📁 5 source files → **30 files**<br>
⚡ 21.0 µs per chunking call, in-process → **55.1 µs**<br>
🎯 8 chunking scenarios put to both → **8 of 8 identical, field for field**

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/ragflow-port/bench/REPORT.md).

---

## What it took to build

⏱️ **1.1 hours** from the first command to the published repository, **1.1** of them active<br>
💬 **430** exchanges with the model<br>
✍️ **421,184** tokens written by the model, **125,521,623** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **63** tests

```bash
python toolkit/tokens.py --port ragflow    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in
[`port-log/`](https://github.com/TylerJewell/akka-specify-harness/tree/main/port-log).

---

## What it does

From the specification:

- **A delimiter field is bare characters and backtick-wrapped multi-character
  tokens, combined and tried longest first.** A one-character delimiter and a
  multi-character one that starts with it never collide.
- **A chunk closes once its running size already exceeds the budget, keeping the
  paragraph that tipped it over.** A paragraph larger than the budget on its own
  always stands alone rather than being split.
- **A backtick-wrapped delimiter turns off the size budget entirely.** Every piece
  the delimiter separates becomes its own chunk, whatever size it is.
- **Overlap carries the tail of one chunk into the next, unconditionally.** It is
  never trimmed to fit the budget, so context never breaks cleanly at a chunk
  boundary.
- **A short Markdown heading is never left on its own.** It is folded into the
  paragraph that follows it before chunks are decided.
- **Re-processing a document with nothing changed reuses its chunks instead of
  redoing the work.** A task's identity for this purpose is its page range plus
  every chunking setting that could have changed its outcome.
- **A document's progress is the average of its tasks', and a task that keeps
  failing is abandoned rather than retried forever.** Three attempts, then it
  stops, and the document is marked failed rather than left running.

Generated documentation lives at [`docs/index.html`](docs/index.html) — open it in a
browser for the entity diagram, the interaction path, and the component reference.

---

## Design decisions

**Text-layer PDF reading.** The original recognizes a page's layout and reads
scanned pages with three machine-learning models and a fourth small classifier,
which is a project of its own size to rebuild faithfully. This port reads only the
words already embedded in the PDF, page by page, so a scanned or image-only PDF
produces nothing here.

**One chunking rule, not ten.** The original offers separate rules for resumes,
tables, slides, question-and-answer pairs, and more. This port carries only the
general-purpose rule, the one every document gets by default and the one every
other rule's task-splitting and progress machinery is shared with.

**A stand-in for the embedding step.** The original calls a configurable external
model to turn a chunk into a vector for similarity search. This port computes a
short, repeatable stand-in from the chunk's own text instead, so the same chunk
always gets the same vector with no network call and no model to obtain.

**Reprocessing runs as a retried step, not a redelivered message.** The original
counts an attempt each time a crashed worker's message comes back around a queue.
Here, a task is a durable, retryable unit of work, and each retry is what counts an
attempt — three of them, then the task is given up on, matching the original's own
three-attempt limit.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/ragflow-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Try it** — create a document, chunk it, read the chunks back:

```bash
curl -s -X POST http://localhost:9041/documents/doc-1 \
  -H "Content-Type: application/json" \
  -d '{"kbId":"kb-1","name":"notes.txt","fileType":"TEXT",
       "text":"First idea here.\nSecond idea follows it.\nThird idea closes it out.\n",
       "parserConfig":{"chunkTokenNum":8,"delimiter":"\n","overlappedPercent":0,
                        "strategy":"OVER_CAP","taskPageSize":12}}'

curl -s -X POST http://localhost:9041/documents/doc-1/parse

curl -s http://localhost:9041/documents/doc-1
curl -s http://localhost:9041/documents/doc-1/chunks
```

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9041**.

### Run the tests

```bash
mvn test
```

63 tests: the chunking and task-splitting rules on their own, the same rules
through the document and task records, and the whole pipeline again with the
service really running.

---

## What you can ask it

| What you want | How to ask |
|---|---|
| Create a document | `POST /documents/{docId}` with `{"kbId","name","fileType","text"}` (or `"pdfBase64"` for a PDF) and optionally `"parserConfig"` |
| Chunk, embed, and index it | `POST /documents/{docId}/parse` |
| Read a document's status and progress | `GET /documents/{docId}` |
| Read the chunks it produced | `GET /documents/{docId}/chunks` |
| Stop it before it finishes | `POST /documents/{docId}/cancel` |
| Preview chunking without creating a document | `POST /bench/chunk` with `{"text","parserConfig","markdown"}` |

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `akka.javasdk.dev-mode.http-port` | `9041` | set in `src/main/resources/application.conf`; the port the service answers on when run locally |

The service calls no model provider — the embedding step is a deterministic
stand-in, not a call to a real model (see Design decisions).

---

## Where it differs from infiniflow/ragflow

Everything not listed here behaves the same way on purpose, including the parts that
look like mistakes.

- **PDF reading is text-layer only.** The original recognizes page layout and runs
  OCR on scanned content with several machine-learning models. This port reads only
  the PDF's own embedded text, page by page — a scanned or image-only page reads as
  empty here.
- **Only the general-purpose chunking rule is ported.** The original has ten; this
  port has the one every document uses by default.
- **Embeddings are a repeatable stand-in, not a real model's output.** Two chunks
  with the same text always get the same vector here; nothing about the vector's
  content is a claim about meaning or similarity.
- **A chunk's identity is its own content and document, hashed differently.** The
  original hashes with xxhash64; this port hashes the same two inputs with SHA-256.
  Nothing here depends on the two systems agreeing on one chunk's exact identity,
  only on each system's own identities being stable.
- **A task's opening progress reading is fixed, not a small random number.** The
  original shows a little forward motion the instant a task is picked up, with no
  meaning behind the exact value. This port shows none until real progress exists.
- **Reprocessing is a retried step, not a redelivered queue message.** Both give up
  after three attempts at the same task; how an attempt is counted differs because
  the underlying mechanisms do (see Design decisions).
- **Chunks stay visible under every task that ever produced or reused them.** The
  original deletes a document's prior chunks before reprocessing it. This port
  keeps every generation's chunks queryable rather than deleting the superseded
  ones, so asking for a document's chunks after a reprocess may briefly show more
  than one generation's worth until a reader filters by the current one.
- **A second, strict merge rule exists but is never chosen automatically.** The
  original always uses the rule that lets a chunk overflow its size budget by one
  paragraph. This port carries the original's own documented alternative — a
  strict rule that never overflows — as a configuration choice, not a default.

---

## Licence

infiniflow/ragflow is Apache License 2.0, © The InfiniFlow Authors. This port
reimplements its behaviour from reading and running the source, with no source
copied; see `ACKNOWLEDGEMENTS.md`.
