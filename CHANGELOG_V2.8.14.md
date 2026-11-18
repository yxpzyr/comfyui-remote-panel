# V2.8.14

Fix V3 dynamic-combo parameters becoming option-definition objects after opening the parameter panel. The panel now displays and submits the actual option keys. Before queue submission, malformed saved selectors are restored from the original workflow configuration where available; valid user selections and explicit sampling values are preserved. The repair applies to the submission copy and leaves original workflow snapshots intact.

Add JVM regression coverage and Android instrumentation coverage for the real parameter spinner and queued submission recovery, including both TextGenerate readers used by the Qwen clothing workflows. Keep the existing SaveVideo format guard and ordinary numeric combo handling.
