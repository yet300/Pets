# Kodee — Codex custom pet

Unofficial Codex pet adaptation of **Kodee**, the Kotlin mascot. The bundle includes the pet manifest, v2 animated atlas, previews, validation result, and install scripts.

> Kodee is JetBrains’ Kotlin mascot. This fan adaptation is not an official JetBrains or OpenAI release. See [JetBrains’ Kotlin mascot page](https://kotlinlang.org/docs/kotlin-brand-assets.html#kotlin-mascot) and follow its mascot guidelines.

## Install

### macOS / Linux

Clone the repository, enter its folder, and run:

    ./install.sh

To replace an existing local Kodee installation:

    ./install.sh --force

### Windows PowerShell

    .\install.ps1

Use `-Force` to replace an existing installation:

    .\install.ps1 -Force

The scripts copy pet.json and spritesheet.webp to `~/.codex/pets/kodee/` (Windows: `%USERPROFILE%\.codex\pets\kodee\`). Then open Codex **Settings → Pets**, select **Refresh**, and choose **Kodee** under custom pets.

## Manual install

Copy this repository’s pet.json and spritesheet.webp to `~/.codex/pets/kodee/` (Windows: `%USERPROFILE%\.codex\pets\kodee\`). Open **Settings → Pets → Refresh**, then select **Kodee**. Keep both files together; the directory name matches the pet ID, `kodee`.

## Package contents

- `pet.json` — metadata; `spriteVersionNumber: 2`
- `spritesheet.webp` — 8×11 atlas, 192×208 pixels per cell
- `preview.png` — all animation rows
- `look-directions.png` — neutral frame and 16 look directions
- `validation.json` — atlas validation result
- `install.sh`, `install.ps1` — install scripts

## Attribution and rights

The Kodee character and Kotlin brand belong to JetBrains. This repository does not claim ownership of or grant a separate license to Kodee or this mascot adaptation. Use and redistribute only as permitted by the [official mascot guidelines](https://kotlinlang.org/docs/kotlin-brand-assets.html#kotlin-mascot). This project is unofficial and not endorsed by JetBrains or OpenAI.
