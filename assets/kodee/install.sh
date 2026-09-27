#!/usr/bin/env bash
set -euo pipefail

force=0
if [[ "${1:-}" == "--force" ]]; then
  force=1
elif [[ $# -gt 0 ]]; then
  echo "Usage: ./install.sh [--force]" >&2
  exit 2
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
pet_id="kodee"
pet_dir="${HOME}/.codex/pets/${pet_id}"

if [[ -e "$pet_dir" && "$force" -ne 1 ]]; then
  echo "Already exists: $pet_dir" >&2
  echo "Use ./install.sh --force to replace its pet.json and spritesheet.webp." >&2
  exit 1
fi

mkdir -p "$pet_dir"
cp "$script_dir/pet.json" "$pet_dir/pet.json"
cp "$script_dir/spritesheet.webp" "$pet_dir/spritesheet.webp"

echo "Installed Kodee to $pet_dir"
echo "In Codex, open Settings → Pets → Refresh, then select Kodee."
