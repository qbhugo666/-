// Neumorphic 9-patch generator: draws surface + dual blurred shadows (light TL / dark BR),
// bakes 9-patch markers, saves PNG. ASCII only.
// Usage: powershell -File make_neu_ninepatch.ps1 <outPath> <size> <cardPad> <cardRadius> <shadowOffset> <blurRadius> <surfHex> <hiHex> <loHex> <mode:raised|inset>
Add-Type -AssemblyName System.Drawing

$out     = $args[0]
[int]$size = $args[1]
[int]$cardPad = $args[2]
[int]$cardRad = $args[3]
[int]$off  = $args[4]
[int]$blur = $args[5]
[string]$surfHex = $args[6]
[string]$hiHex = $args[7]
[string]$loHex = $args[8]
[string]$mode = $args[9]

function HexToColor([string]$hex) {
    $h = $hex.TrimStart('#')
    return [System.Drawing.Color]::FromArgb(255,
        [Convert]::ToInt32($h.Substring(0,2),16),
        [Convert]::ToInt32($h.Substring(2,2),16),
        [Convert]::ToInt32($h.Substring(4,2),16))
}
function New-RoundRectPath([int]$x,[int]$y,[int]$w,[int]$h,[int]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $p.AddArc($x, $y, $d, $d, 180, 90)
    $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

$surfC = HexToColor $surfHex
$hiC   = HexToColor $hiHex
$loC   = HexToColor $loHex

# card rect inside the bitmap
$cx = $cardPad; $cy = $cardPad
$cw = $size - $cardPad * 2; $ch = $size - $cardPad * 2

$bmp = New-Object System.Drawing.Bitmap($size, $size)

if ($mode -eq "raised") {
    # dual shadows on a transparent canvas, then blur, then crisp surface on top
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $loPath = New-RoundRectPath ($cx + $off) ($cy + $off) $cw $ch $cardRad
    $loB = New-Object System.Drawing.SolidBrush $loC
    $g.FillPath($loB, $loPath)
    $hiPath = New-RoundRectPath ($cx - $off) ($cy - $off) $cw $ch $cardRad
    $hiB = New-Object System.Drawing.SolidBrush $hiC
    $g.FillPath($hiB, $hiPath)
    $g.Dispose()
    BoxBlurBitmap $bmp $blur 3
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $surfPath = New-RoundRectPath $cx $cy $cw $ch $cardRad
    $surfB = New-Object System.Drawing.SolidBrush $surfC
    $g.FillPath($surfB, $surfPath)
    $g.Dispose()
}
else {
    # inset: surface with dark TL inner rim + light BR inner rim (concave)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $surfB = New-Object System.Drawing.SolidBrush $surfC
    $g.FillRectangle($surfB, 0, 0, $size, $size)
    $inset = [Math]::Max(4, [int]($blur / 2))
    $dR = New-RoundRectPath ($cx - $inset) ($cy - $inset) ($cw + $inset*2) ($ch + $inset*2) ($cardRad + $inset)
    $dPath = New-RoundRectPath $cx $cy $cw $ch $cardRad
    # dark rim along top/left inside: gradient dark -> transparent toward BR
    $darkGrad = New-Object System.Drawing.Drawing2D.LinearBrush(
        [System.Drawing.Point]::new($cx, $cy),
        [System.Drawing.Point]::new(($cx + $cw), ($cy + $ch)),
        $loC, [System.Drawing.Color]::FromArgb(0, $loC.R, $loC.G, $loC.B))
    $g.FillPath($darkGrad, $dPath)
    # light rim along bottom/right inside
    $lGrad = New-Object System.Drawing.Drawing2D.LinearBrush(
        [System.Drawing.Point]::new(($cx + $cw), ($cy + $ch)),
        [System.Drawing.Point]::new($cx, $cy),
        $hiC, [System.Drawing.Color]::FromArgb(0, $hiC.R, $hiC.G, $hiC.B))
    $lPath = New-RoundRectPath ($cx - $inset) ($cy - $inset) ($cw + $inset*2) ($ch + $inset*2) ($cardRad + $inset)
    $g.FillPath($lGrad, $lPath)
    $g.Dispose()
}

# ---- 9-patch markers (1px black on border) ----
$mk = [System.Drawing.Color]::Black
$stretchA = [int]($size / 2) - 30
$stretchB = [int]($size / 2) + 30
for ($x = $stretchA; $x -le $stretchB; $x++) { $bmp.SetPixel($x, 0, $mk); $bmp.SetPixel($x, $size - 1, $mk) }
for ($y = $stretchA; $y -le $stretchB; $y++) { $bmp.SetPixel(0, $y, $mk); $bmp.SetPixel($size - 1, $y, $mk) }
# content padding markers: card inner area
$padA = $cardPad + 20; $padB = $size - $cardPad - 20
for ($x = $padA; $x -le $padB; $x++) { $bmp.SetPixel($x, $size - 1, $mk) }
for ($y = $padA; $y -le $padB; $y++) { $bmp.SetPixel($size - 1, $y, $mk) }

$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output ("saved: {0} ({1}x{2}, mode={3})" -f $out, $size, $size, $mode)
