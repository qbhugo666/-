# Replace the avatar at the center of a payment QR card with a NATIVE-LOOKING
# brand avatar: rounded-corner square, brand blue #246BFE fill, white glyph.
# WeChat style: avatar square directly (covers photo + green badge).
# Alipay style: white rounded frame first, then the brand square inside.
# Usage:
#   powershell -File make_qr_avatar.ps1 <in> <glyph> <out> <style> <yMinFrac> <yMaxFrac>
#   style = wechat | alipay
# ASCII only on purpose (PS5 no-BOM hazard).
param(
    [string]$inPath,
    [string]$glyphPath,
    [string]$outPath,
    [string]$style = "wechat",
    [double]$yMinFrac = 0.33,
    [double]$yMaxFrac = 0.67
)
Add-Type -AssemblyName System.Drawing

$bmp = New-Object System.Drawing.Bitmap($inPath)
$W = $bmp.Width; $H = $bmp.Height

# ---- 1. locate the colorful avatar pixels (exclude blue/green bg) ----
$minX = $W; $maxX = 0; $minY = $H; $maxY = 0; $found = $false
$x0 = [int]($W * 0.30); $x1 = [int]($W * 0.70)
$y0 = [int]($H * $yMinFrac); $y1 = [int]($H * $yMaxFrac)
for ($y = $y0; $y -lt $y1; $y += 2) {
    for ($x = $x0; $x -lt $x1; $x += 2) {
        $p = $bmp.GetPixel($x, $y)
        $sat = [Math]::Max($p.R, [Math]::Max($p.G, $p.B)) - [Math]::Min($p.R, [Math]::Min($p.G, $p.B))
        if ($sat -le 45) { continue }
        $blueish  = ($p.B -gt $p.R + 40) -and ($p.B -gt $p.G + 40)
        $greenish = ($p.G -gt $p.R + 40) -and ($p.G -gt $p.B + 40)
        if ($blueish -or $greenish) { continue }
        $found = $true
        if ($x -lt $minX) { $minX = $x }; if ($x -gt $maxX) { $maxX = $x }
        if ($y -lt $minY) { $minY = $y }; if ($y -gt $maxY) { $maxY = $y }
    }
}
if (-not $found) { Write-Output "ERROR: no avatar pixels found"; exit 1 }
$cx = ($minX + $maxX) / 2.0; $cy = ($minY + $maxY) / 2.0
Write-Output ("avatar bbox: {0}..{1} x {2}..{3} center=({4},{5}) style={6}" -f $minX, $maxX, $minY, $maxY, $cx, $cy, $style)

# ---- 2. rounded-rect path helper ----
function New-RoundedRect([single]$x, [single]$y, [single]$w, [single]$h, [single]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $p.AddArc($x, $y, $d, $d, 180, 90)
    $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

$out = New-Object System.Drawing.Bitmap($bmp)
$g2 = [System.Drawing.Graphics]::FromImage($out)
$g2.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$blue = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 0x24, 0x6B, 0xFE))
$white = [System.Drawing.Brushes]::White
$glyph = New-Object System.Drawing.Bitmap($glyphPath)

if ($style -eq "wechat") {
    # avatar square covers photo + badge; white frame first for breathing room
    $fw = ($maxX - $minX) + 28; $fh = ($maxY - $minY) + 28
    $fx = $minX - 14; $fy = $minY - 14; $fr = 22
    $fpath = New-RoundedRect $fx $fy $fw $fh $fr
    $g2.FillPath($white, $fpath)
    $w = ($maxX - $minX) + 2; $h = ($maxY - $minY) + 2
    $x = $minX - 1; $y = $minY - 1; $r = [Math]::Min($w, $h) * 0.17
    $path = New-RoundedRect $x $y $w $h $r
    $g2.FillPath($blue, $path)
    $gw = [int]($w * 0.52)
    $gh = [int]($gw * $glyph.Height / $glyph.Width)
    $g2.DrawImage($glyph, [int]($x + ($w - $gw) / 2), [int]($y + ($h - $gh) / 2), $gw, $gh)
}
else {
    # alipay: white rounded frame first (covers chopper fully), then brand square inside
    $frameW = ($maxX - $minX) + 40; $frameH = ($maxY - $minY) + 40
    $fx = $minX - 20; $fy = $minY - 20; $fr = 24
    $fpath = New-RoundedRect $fx $fy $frameW $frameH $fr
    $g2.FillPath($white, $fpath)
    $iw = ($maxX - $minX) + 4; $ih = ($maxY - $minY) + 4
    $ix = $minX - 2; $iy = $minY - 2; $ir = 16
    $ipath = New-RoundedRect $ix $iy $iw $ih $ir
    $g2.FillPath($blue, $ipath)
    $gw = [int]($iw * 0.52)
    $gh = [int]($gw * $glyph.Height / $glyph.Width)
    $g2.DrawImage($glyph, [int]($ix + ($iw - $gw) / 2), [int]($iy + ($ih - $gh) / 2), $gw, $gh)
}
$g2.Dispose()

$out.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose(); $out.Dispose(); $glyph.Dispose()
Write-Output ("saved: {0}" -f $outPath)
