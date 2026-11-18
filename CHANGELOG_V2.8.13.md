# V2.8.13 - Mobile H3 Director

- Native storyboard editor with per-shot prompt, duration and previous-shot continuity; add, remove and reorder cards.
- Built-in two-shot practice and six-shot long-video projects, with draft state separated by workflow and collection variant.
- Text-to-video, first/last-frame mode and image-reference mode. Match the installed H3 model and acceleration LoRA by mode.
- Image selection and clearing update the submitted timeline, with no fallback to removed reference pictures. Upload and submission use an isolated snapshot.
- Desktop output node concatenates the exact segment paths returned by the current Director report and emits standard video metadata for the existing queue and gallery.
- Per-shot progress and final merge progress. Existing workflow compilation, random seeds, text generation and media playback remain covered by regression tests.

This first mobile version runs the whole storyboard. External group wiring, reference video/audio, semantic bridges and refinement nodes require the desktop workflow. No claim of physical-phone validation until a device run is available.
