# Project status

Version: 1.1.0

## Completed
- Android Chinese remote panel
- Termius-local port connection
- Normal ComfyUI workflow JSON auto-conversion via `/object_info`
- API workflow JSON compatibility
- Image upload and loader-node replacement
- Queue / prompt polling / output preview
- Save output to Android Pictures/ComfyRemote
- History gallery
- GitHub Actions APK build

## Converter notes
The V1.1 converter resolves regular links, PrimitiveNode values, Reroute nodes and common bypass paths. It maps widget values using the live node schema returned by the user's ComfyUI server. Frontend-only nodes, subgraphs, unusual Get/Set implementations, or missing custom-node packs may still require an API-format workflow.
