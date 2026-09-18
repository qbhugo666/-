# Generate neumorphic nine-patch backgrounds (light/dark x card/hero/pressed)
# ASCII only. Uses neumaker.cs compiled via Add-Type.
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition ([IO.File]::ReadAllText('E:\VoiceControl\scripts\neumaker.cs')) -ReferencedAssemblies System.Drawing

$out = 'E:\VoiceControl\design\support\neu9'
New-Item -ItemType Directory -Force $out | Out-Null

# light card: raised
[NeuMaker]::Make("$out\card_light.9.png",    300, 60, 48, 10, 10, '#F0F0F3', '#FFFFFF', '#A3B2CC', 'raised')
# dark card: raised (independent dark recipe)
[NeuMaker]::Make("$out\card_dark.9.png",     300, 60, 48, 10, 10, '#23262C', '#2E333B', '#0F1114', 'raised')
# hero: bigger radius + deeper shadows
[NeuMaker]::Make("$out\hero_light.9.png",    360, 70, 66, 12, 12, '#F0F0F3', '#FFFFFF', '#A3B2CC', 'raised')
[NeuMaker]::Make("$out\hero_dark.9.png",     360, 70, 66, 12, 12, '#23262C', '#2E333B', '#0F1114', 'raised')
# pressed: inset concave (pressed into surface) — kit rule: active = extrude to intrude
[NeuMaker]::Make("$out\card_pressed_light.9.png", 300, 60, 48, 4, 8, '#DDE2EB', '#FFFFFF', '#A3B2CC', 'inset')
[NeuMaker]::Make("$out\card_pressed_dark.9.png",  300, 60, 48, 4, 8, '#1B1E23', '#2E333B', '#0F1114', 'inset')

Write-Output 'ALL GENERATED'
