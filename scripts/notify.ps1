# Toast notifier for task-completion pings (bottom-right).
# ASCII-only file on purpose: PowerShell 5 garbles non-ASCII literals without BOM.
# Pass Chinese text via -Title/-Message; the process command line is Unicode-safe.
param(
    [string]$Title = "ZCode",
    [string]$Message = "Task completed"
)

$ErrorActionPreference = "Stop"

try {
    [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
    [Windows.UI.Notifications.ToastNotification, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
    [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, ContentType = WindowsRuntime] | Out-Null

    $escTitle = [System.Security.SecurityElement]::Escape($Title)
    $escMsg = [System.Security.SecurityElement]::Escape($Message)

    $xml = "<toast scenario=""reminder""><visual><binding template=""ToastGeneric""><text>$escTitle</text><text>$escMsg</text></binding></visual><audio src=""ms-winsoundevent:Notification.Default""/></toast>"

    $doc = New-Object Windows.Data.Xml.Dom.XmlDocument
    $doc.LoadXml($xml)
    $toast = New-Object Windows.UI.Notifications.ToastNotification $doc
    $appId = "{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\WindowsPowerShell\v1.0\powershell.exe"
    [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($appId).Show($toast)
    Write-Output "TOAST_OK"
} catch {
    Write-Output ("TOAST_FAIL: " + $_.Exception.Message)
    exit 1
}
