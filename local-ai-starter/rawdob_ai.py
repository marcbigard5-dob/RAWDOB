#!/usr/bin/env python3
"""Prepare structured prompts for a local coding model. Does not execute code."""
import argparse
import sys

SYSTEM = """You are RAWDOB AI, a local coding assistant.
Produce practical, complete code for the user's stated task.
When useful, include:
1. complete source code,
2. dependencies and exact installation commands,
3. exact run command,
4. assumptions and limitations,
5. a small test or verification step.
Do not claim code was executed or tested unless evidence is provided.
Never execute code or shell commands yourself.
For security testing, keep work scoped to systems the user owns or is explicitly authorized to assess.
Prefer safe defaults, explicit target configuration, timeouts, rate limits, and clear logging.
If a request is ambiguous, state assumptions briefly and provide a conservative implementation.
"""

def main():
    parser = argparse.ArgumentParser(
        description="Prepare a prompt for a local GGUF coding model."
    )
    parser.add_argument("task", nargs="*", help="Describe the script you want.")
    parser.add_argument("--language", default="Python")
    parser.add_argument("--output", default="", help="Optional output text file")
    args = parser.parse_args()

    task = " ".join(args.task).strip() or sys.stdin.read().strip()
    if not task:
        parser.error("Provide a task as arguments or through stdin.")

    prompt = (
        "<|im_start|>system\n" + SYSTEM + "\n<|im_end|>\n"
        "<|im_start|>user\n"
        f"Preferred language: {args.language}\n"
        "Task description:\n" + task + "\n"
        "Return complete code, dependencies, installation and run commands, "
        "and verification steps.\n"
        "<|im_end|>\n<|im_start|>assistant\n"
    )
    if args.output:
        with open(args.output, "w", encoding="utf-8") as handle:
            handle.write(prompt)
        print(f"Prompt saved to {args.output}")
    else:
        print(prompt, end="")

if __name__ == "__main__":
    main()
