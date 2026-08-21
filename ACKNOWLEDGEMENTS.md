# Acknowledgements

This project is a port of **[infiniflow/ragflow](https://github.com/infiniflow/ragflow)**.

- **Licence and copyright.** `infiniflow/ragflow` is licensed under the Apache
  License, Version 2.0. Every source file's own header attributes copyright to
  "The InfiniFlow Authors" (e.g. `rag/nlp/__init__.py:1-15`,
  `rag/nlp/delim.py:1-15`).
- **Was anything copied verbatim?** No. Every file in `ragflow-akka/` is
  independently written Java, produced by reading the source (cited by file and
  line throughout `specs/SPEC-001-ragflow.md` and `docs/question-log.md`) and
  running it (`ragflow-port/probes/probe_01.py`, `bench/`), not by transcription.
  No prompts, fixtures, schemas, or test corpora were copied from the source.
- **Is behaviour derived even where no text was copied?** Yes, plainly. The
  delimiter grammar (`Delimiter.java`), the merge/overlap algorithm
  (`Chunker.java`), the Markdown short-header rule, PDF task-window splitting, the
  task digest, and the progress-rollup arithmetic are all deliberate,
  line-for-line-verified reimplementations of rules the source's `rag.nlp`,
  `rag.app.naive`, and `api.db.services` modules define — SPEC-001 §3 cites the
  exact source rule each one reproduces. That derivation is the entire point of a
  port and is not something to be coy about.
- **What licence does that force on this project?** Nothing copied, so nothing
  forced — `ragflow-akka` carries its own licence (Apache-2.0, matching the
  source, chosen for compatibility rather than obligation).

## Also used

- Akka (the Akka SDK for Java, 3.6.3)
- Apache PDFBox 3.0.3 — reads a PDF's embedded text layer (SPEC-001 §4 decision 1)
- JTokkit 1.1.0 — `cl100k_base` token counting, matching the source's `tiktoken` usage
