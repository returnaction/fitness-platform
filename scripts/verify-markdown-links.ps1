[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$normalizedRepositoryRoot = (Resolve-Path -LiteralPath $repositoryRoot).Path.TrimEnd('\', '/')
$markdownFiles = Get-ChildItem -Path $repositoryRoot -Filter '*.md' -File -Recurse |
    Where-Object { $_.FullName -notmatch '[\\/]\.git[\\/]' }
$linkPattern = '\[[^\]]+\]\((?<target>[^)\s]+)(?:\s+[^)]*)?\)'
$failures = [System.Collections.Generic.List[string]]::new()

foreach ($file in $markdownFiles) {
    $lineNumber = 0

    foreach ($line in Get-Content -LiteralPath $file.FullName) {
        $lineNumber++

        foreach ($match in [regex]::Matches($line, $linkPattern)) {
            $target = $match.Groups['target'].Value.Trim('<>')

            if ($target -match '^(https?://|mailto:|tel:|#)') {
                continue
            }

            $targetPath = $target.Split('#', 2)[0].Split('?', 2)[0]
            if ([string]::IsNullOrWhiteSpace($targetPath)) {
                continue
            }

            $resolvedPath = [System.IO.Path]::GetFullPath((Join-Path $file.DirectoryName $targetPath))
            if (-not (Test-Path -LiteralPath $resolvedPath)) {
                $relativeFile = $file.FullName.Substring($normalizedRepositoryRoot.Length).TrimStart('\', '/')
                $failures.Add("${relativeFile}:${lineNumber} -> ${target}")
            }
        }
    }
}

if ($failures.Count -gt 0) {
    Write-Error "Broken local Markdown links:`n$($failures -join "`n")"
}

Write-Host "Validated local Markdown links in $($markdownFiles.Count) files."
