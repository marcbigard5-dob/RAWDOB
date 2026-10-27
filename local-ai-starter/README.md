# RAWDOB AI — Local Coding Assistant Starter

This folder is a starter for a local-first coding assistant on Android/Termux.

## Scope
- This is an orchestration and prompt layer, not a newly trained foundation model.
- It never executes generated code or shell commands.
- It does not download model weights during setup.
- Proposed runtime: [llama.cpp](https://github.com/ggml-org/llama.cpp).
- Proposed model: [Qwen2.5-Coder-1.5B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF).

## Device-aware plan
Target device: ARM64 Android, around 5.6 GB total RAM. Actual RAM available to Termux is lower. Begin with a quantized model and context of 2048 or 4096 tokens. The Q4_K_M file is approximately 1.12 GB; runtime memory exceeds the file size. Performance must be tested on the phone.

## Setup references
- Official Android/Termux instructions: https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md
- Build guide: https://github.com/ggml-org/llama.cpp/blob/master/docs/build.md

## Milestones
1. Check whether Termux provides a prebuilt llama.cpp package.
2. If not, build llama.cpp from its official source using the Android instructions.
3. Download the GGUF model separately after confirming enough storage.
4. Run a short prompt with a small context.
5. Connect the model to the prompt helper in this folder.
6. Later, create an evaluation set and consider fine-tuning on suitable hardware.

## Safety and reliability
Review every generated command before running it. Security-testing scripts should be used only on systems you own or are explicitly authorized to assess. Never assume code is tested just because a model generated it.

## License notes
This starter code is provided under the MIT License. The base model and runtime have their own licenses; check their current terms before redistribution or commercial use.
