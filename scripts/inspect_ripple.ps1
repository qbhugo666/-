# Pixel forensics for ripple clipping (ASCII only; PS5 safe)
# Usage: powershell -File inspect_ripple.ps1 <png> <centerX> <logoTop> <logoBottom>
Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile($args[0])
[int]$cx = $args[1]; [int]$vtop = $args[2]; [int]$vbot = $args[3]

function Is-Ripple($p) {
    # brand blue #246BFE at low alpha over light bg => blue dominant, not too dark
    return ($p.B -gt $p.R + 20) -and ($p.B -gt 180) -and ($p.R -gt 100)
}

Write-Output "=== vertical scan at x=$cx (card top->circle center) ==="
for ($y = $vtop - 130; $y -lt $vbot; $y += 2) {
    $p = $bmp.GetPixel($cx, $y)
    if (Is-Ripple $p) { Write-Output ("y={0} RIPPLE rgb=({1},{2},{3})" -f $y, $p.R, $p.G, $p.B) }
}
Write-Output "=== horizontal scan at y (logo top - 20px): row above view bounds ==="
$yy = $vtop - 20
$row = @()
for ($x = $cx - 260; $x -le $cx + 260; $x += 2) {
    $p = $bmp.GetPixel($x, $yy)
    if (Is-Ripple $p) { $row += $x }
}
if ($row.Count -eq 0) { Write-Output "NO ripple pixels in row y=$yy" }
else { Write-Output ("y={0} ripple x-range: {1}..{2}" -f $yy, $row[0], $row[$row.Count-1]) }
Write-Output "=== horizontal scan at y (logo top - 60px): near card inner edge ==="
$yy2 = $vtop - 60
$row2 = @()
for ($x = $cx - 260; $x -le $cx + 260; $x += 2) {
    $p = $bmp.GetPixel($x, $yy2)
    if (Is-Ripple $p) { $row2 += $x }
}
if ($row2.Count -eq 0) { Write-Output "NO ripple pixels in row y=$yy2" }
else { Write-Output ("y={0} ripple x-range: {1}..{2}" -f $yy2, $row2[0], $row2[$row2.Count-1]) }
$bmp.Dispose()
