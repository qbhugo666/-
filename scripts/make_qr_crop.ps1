# Crop just the QR code square out of a payment card image.
# Detects the black QR modules inside a scan window, crops with padding.
# Usage: powershell -File make_qr_crop.ps1 <in> <out> <xMin> <xMax> <yMin> <yMax>
# Fractions of image size. ASCII only.
param(
    [string]$inPath,
    [string]$outPath,
    [double]$xMinF = 0.2,
    [double]$xMaxF = 0.8,
    [double]$yMinF = 0.2,
    [double]$yMaxF = 0.8
)
Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap($inPath)
$W = $bmp.Width; $H = $bmp.Height
$minX = $W; $maxX = 0; $minY = $H; $maxY = 0; $found = $false
$x0 = [int]($W * $xMinF); $x1 = [int]($W * $xMaxF)
$y0 = [int]($H * $yMinF); $y1 = [int]($H * $yMaxF)
for ($y = $y0; $y -lt $y1; $y++) {
    for ($x = $x0; $x -lt $x1; $x++) {
        $p = $bmp.GetPixel($x, $y)
        if ($p.R -lt 90 -and $p.G -lt 90 -and $p.B -lt 90) {
            $found = $true
            if ($x -lt $minX) { $minX = $x }
            if ($x -gt $maxX) { $maxX = $x }
            if ($y -lt $minY) { $minY = $y }
            if ($y -gt $maxY) { $maxY = $y }
        }
    }
}
if (-not $found) { Write-Output "ERROR: no QR pixels"; exit 1 }
$pad = 12
$cx = $minX - $pad; $cy = $minY - $pad
$cw = ($maxX - $minX) + $pad * 2; $ch = ($maxY - $minY) + $pad * 2
# QR is square: force square crop (top-aligned) to drop name-text caught below
if ($ch -gt $cw) { $ch = $cw }
# clamp to image
if ($cx -lt 0) { $cx = 0 }; if ($cy -lt 0) { $cy = 0 }
if ($cx + $cw -gt $W) { $cw = $W - $cx }
if ($cy + $ch -gt $H) { $ch = $H - $cy }
$crop = $bmp.Clone([System.Drawing.Rectangle]::new($cx, $cy, $cw, $ch), $bmp.PixelFormat)
$crop.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
Write-Output ("cropped {0}: rect({1},{2},{3},{4})" -f $outPath, $cx, $cy, $cw, $ch)
$bmp.Dispose(); $crop.Dispose()
