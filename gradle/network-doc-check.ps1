[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$docFolder = Join-Path $workspace 'docs'
$utf8 = New-Object System.Text.UTF8Encoding($false, $true)
$documents = @(Get-ChildItem -LiteralPath $docFolder -Filter 'bee-processing-network-*.md' -File)
if ($documents.Count -eq 0) { throw 'Network documents are missing' }
$texts = @{}
$anchors = @{}
$links = 0

foreach ($document in $documents) {
    $bytes = [IO.File]::ReadAllBytes($document.FullName)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191) {
        throw "UTF-8 BOM: $($document.Name)"
    }
    $content = $utf8.GetString($bytes)
    if ($content.IndexOf([char]0xFFFD) -ge 0) { throw "Replacement character: $($document.Name)" }
    $texts[$document.FullName] = $content
    $ids = @{}
    foreach ($match in [regex]::Matches($content, '<a\s+id="([^"]+)"\s*></a>')) {
        $id = $match.Groups[1].Value
        if ($ids.ContainsKey($id)) { throw "Duplicate anchor: $($document.Name)#$id" }
        $ids[$id] = $true
    }
    $anchors[$document.FullName] = $ids
}
foreach ($document in $documents) {
    $lines = $texts[$document.FullName] -split "\r?\n"
    for ($i = 0; $i -lt $lines.Count; $i++) {
        foreach ($match in [regex]::Matches($lines[$i], '\]\(([^\s)]+)\)')) {
            $link = $match.Groups[1].Value
            if ($link -match '^[a-zA-Z][a-zA-Z0-9+.-]*:' -or $link.StartsWith('//')) { continue }
            $parts = $link -split '#', 2
            $relative = [Uri]::UnescapeDataString($parts[0])
            $target = if ($relative.Length -eq 0) { $document.FullName }
                else { [IO.Path]::GetFullPath((Join-Path $document.DirectoryName $relative)) }
            if (-not (Test-Path -LiteralPath $target)) {
                throw "Missing link at $($document.Name):$($i + 1): $link"
            }
            if ($parts.Count -eq 2 -and $anchors.ContainsKey($target) -and $parts[1] -match '^(s[0-9]|current)') {
                if (-not $anchors[$target].ContainsKey($parts[1])) {
                    throw "Missing anchor at $($document.Name):$($i + 1): $link"
                }
            }
            $links++
        }
    }
}
if (-not $anchors[(Join-Path $docFolder 'bee-processing-network-roadmap.md')].ContainsKey('current') -or
    -not $anchors[(Join-Path $docFolder 'bee-processing-network-roadmap.md')].ContainsKey('s14')) {
    throw 'The current state and execution queue anchors are required'
}
Write-Output "Network documents passed: $($documents.Count) UTF-8 files, unique anchors, $links local links."
Write-Output 'Scope: document structure only; no compilation, game or stage acceptance is implied.'
