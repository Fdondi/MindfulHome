# Refreshes Material Icons Outlined font + codepoints from google/material-design-icons (Apache 2.0).
$root = Split-Path -Parent $PSScriptRoot
$fontDir = Join-Path $root "app\src\main\res\font"
$assetsDir = Join-Path $root "app\src\main\assets"
New-Item -ItemType Directory -Force -Path $fontDir, $assetsDir | Out-Null
$base = "https://raw.githubusercontent.com/google/material-design-icons/master/font"
Invoke-WebRequest -Uri "$base/MaterialIconsOutlined-Regular.otf" -OutFile (Join-Path $fontDir "material_icons_outlined.otf") -UseBasicParsing
Invoke-WebRequest -Uri "$base/MaterialIconsOutlined-Regular.codepoints" -OutFile (Join-Path $assetsDir "material_icons_outlined.codepoints") -UseBasicParsing
