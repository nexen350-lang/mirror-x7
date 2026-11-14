param(
    [string]$Output = (Join-Path $PSScriptRoot '..\docs\privacy-policy.html'),
    [string]$Project = (Join-Path $PSScriptRoot '..')
)
# Renders the privacy policy the app bundles (app/src/main/assets/legal/privacy-policy.md) into one
# self-contained web page, with the app's name and the developer's details from publisher.properties.
# Publish the page at a public HTTPS URL (such as GitHub Pages) and set privacyPolicyUrl in
# publisher.properties when building a signed release distribution.
# Uses the same rules as the app: '# ' title, '## ' headings, '- ' bullets, paragraphs, **bold**.
$ErrorActionPreference = 'Stop'
$markdown = Get-Content -LiteralPath (Join-Path $Project 'app\src\main\assets\legal\privacy-policy.md') -Raw
$strings = [xml](Get-Content -LiteralPath (Join-Path $Project 'app\src\main\res\values\strings.xml') -Raw)
$app = ($strings.resources.string | Where-Object { $_.name -eq 'app_name' }).'#text'
$publisher = @{}
# The same reading as the build's Properties.load(Reader): UTF-8 text, with Java's backslash escapes
# (\u00e9, \t, \\) decoded, so the page names the developer exactly as the app does.
function Unescape([string]$value) {
    [regex]::Replace($value, '\\(u[0-9a-fA-F]{4}|.)', {
        param($m)
        $escape = $m.Groups[1].Value
        switch -CaseSensitive ($escape) {
            't' { "`t" } 'n' { "`n" } 'r' { "`r" } 'f' { "`f" }
            default { if ($escape.Length -eq 5) { [string][char][Convert]::ToInt32($escape.Substring(1), 16) } else { $escape } }
        }
    })
}
foreach ($line in Get-Content -LiteralPath (Join-Path $Project 'publisher.properties') -Encoding utf8) {
    if ($line -match '^\s*([A-Za-z]+)\s*[=:]\s*(.*)$') { $publisher[$Matches[1]] = (Unescape $Matches[2]).Trim() }
}
$developer = $publisher['publisherName']
$contact = $publisher['publisherEmail']
$missing = @()
if (-not $developer) { $developer = 'the project maintainer'; $missing += 'publisherName' }
if (-not $contact) { $contact = 'the repository issue tracker or published maintainer contact'; $missing += 'publisherEmail' }
$text = $markdown.Replace('{{APP}}', $app).Replace('{{DEVELOPER}}', $developer).Replace('{{CONTACT}}', $contact)

function Inline([string]$value) {
    $escaped = [Net.WebUtility]::HtmlEncode($value)
    [regex]::Replace($escaped, '\*\*(.+?)\*\*', '<strong>$1</strong>')
}
$html = [Collections.Generic.List[string]]::new()
$title = $app
$block = [Collections.Generic.List[string]]::new()
$bullet = $false
$inList = $false
function Flush {
    if ($block.Count -gt 0) {
        $content = Inline ($block -join ' ')
        if ($script:bullet) {
            if (-not $script:inList) { $html.Add('<ul>'); $script:inList = $true }
            $html.Add("<li>$content</li>")
        } else {
            if ($script:inList) { $html.Add('</ul>'); $script:inList = $false }
            $html.Add("<p>$content</p>")
        }
    }
    $block.Clear(); $script:bullet = $false
}
foreach ($raw in ($text -split "`r?`n")) {
    $line = $raw.Trim()
    if (-not $line) { Flush; continue }
    if ($line.StartsWith('## ') -or $line.StartsWith('# ')) {
        Flush
        if ($inList) { $html.Add('</ul>'); $inList = $false }
        if ($line.StartsWith('## ')) { $html.Add("<h2>$(Inline $line.Substring(3))</h2>") }
        else { $title = $line.Substring(2); $html.Add("<h1>$(Inline $title)</h1>") }
        continue
    }
    if ($line.StartsWith('- ')) { Flush; $bullet = $true; $block.Add($line.Substring(2)); continue }
    $block.Add($line)
}
Flush
if ($inList) { $html.Add('</ul>') }
$page = @"
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$([Net.WebUtility]::HtmlEncode($title))</title>
<style>
body { font: 16px/1.6 -apple-system, "Segoe UI", Roboto, sans-serif; max-width: 46rem; margin: 2rem auto; padding: 0 1rem; color: #1c1c1e; }
h1 { font-size: 1.8rem; line-height: 1.25; } h2 { font-size: 1.25rem; margin-top: 2rem; }
li { margin: .4rem 0; } @media (prefers-color-scheme: dark) { body { background: #000; color: #f2f2f7; } }
</style>
</head>
<body>
$($html -join "`n")
</body>
</html>
"@
[IO.File]::WriteAllText([IO.Path]::GetFullPath($Output), $page.Replace("`r`n", "`n"), [Text.UTF8Encoding]::new($false))
"Wrote $([IO.Path]::GetFullPath($Output))"
if ($missing) { Write-Warning "Not ready to publish: $($missing -join ' and ') not set in publisher.properties; the page shows placeholders." }
