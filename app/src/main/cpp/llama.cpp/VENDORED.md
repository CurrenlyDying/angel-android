llama.cpp v0.5.0 (vendored, trimmed)

Source: https://github.com/ggml-org/llama.cpp/archive/refs/tags/v0.5.0.tar.gz
SHA-256 of tarball: fef9ed754f4e031fb5c663c29260feda4ebc241abb68d64a81c0f1df5f1748e2
License: MIT (see LICENSE and licenses/)

Kept: CMakeLists.txt cmake/ include/ src/ common/ vendor/ ggml/ LICENSE licenses/
Removed: models, docs, examples, tools, tests, scripts, python, and unused GPU/NPU ggml backends
(cuda sycl vulkan opencl metal hexagon cann openvino webgpu et virtgpu musa hip zdnn zendnn).
Only the CPU backend is built (runtime-selected ARM variants).
