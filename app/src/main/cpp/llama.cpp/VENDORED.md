llama.cpp v0.8.0 (vendored, trimmed)

Source: https://github.com/ggml-org/llama.cpp/archive/refs/tags/v0.8.0.tar.gz
SHA-256 of tarball: 51b1e025d7b12e83fa55736e65bc254acd9d23e49f546be7531368fef82210ee
Upstream commit (v0.8.0 and b11960): 8642541b255bfb5dc39cd351a178be09013a55d6
License: MIT (see LICENSE and licenses/)

Kept: CMakeLists.txt cmake/ include/ src/ common/ vendor/ ggml/ LICENSE licenses/
Removed: models, docs, examples, tools, tests, scripts, python, app, pocs, and unused GPU/NPU ggml backends
(cuda sycl vulkan opencl metal hexagon cann openvino webgpu et virtgpu musa hip zdnn zendnn).
Only the CPU backend is built (runtime-selected ARM variants).

Upgrade from v0.5.0: adds LFM3 architecture support and upstream handling of absent optional
Qwen3.5 MTP tensors. Upstream source files are unmodified; Angel's JNI integration lives
one directory above, in angel_llama.cpp.
The parent CMake pins upstream build metadata independently of Angel's Git history.
