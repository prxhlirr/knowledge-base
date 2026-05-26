param(
    [string]$EsUrl = "http://127.0.0.1:9200",
    [string]$SnapshotRepo = "kb_local_repo",
    [string]$SnapshotLocation = "E:\es_snapshots",
    [string]$SnapshotName,
    [string]$CreateSnapshotIndex = "",
    [string]$ForceMergeIndex = "",
    [switch]$RegisterRepo,
    [switch]$CreateSnapshot,
    [switch]$ListSnapshots,
    [switch]$ForceMerge,
    [switch]$WaitForCompletion
)

$ErrorActionPreference = "Stop"

function Invoke-EsJson {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null
    )
    $uri = "$EsUrl$Path"
    if ($null -eq $Body) {
        return Invoke-RestMethod -Method $Method -Uri $uri
    }
    $json = $Body | ConvertTo-Json -Depth 10
    return Invoke-RestMethod -Method $Method -Uri $uri -ContentType "application/json" -Body $json
}

if ($RegisterRepo) {
    New-Item -ItemType Directory -Force -Path $SnapshotLocation | Out-Null
    Invoke-EsJson -Method Put -Path "/_snapshot/$SnapshotRepo" -Body @{
        type = "fs"
        settings = @{
            location = $SnapshotLocation
            compress = $true
        }
    }
}

if ($CreateSnapshot) {
    if ([string]::IsNullOrWhiteSpace($SnapshotName)) {
        $SnapshotName = "kb_snapshot_" + (Get-Date -Format "yyyyMMdd_HHmmss")
    }
    $path = "/_snapshot/$SnapshotRepo/$SnapshotName"
    if ($WaitForCompletion) {
        $path = "$path`?wait_for_completion=true"
    }
    $body = @{
        include_global_state = $false
    }
    if (-not [string]::IsNullOrWhiteSpace($CreateSnapshotIndex)) {
        $body.indices = $CreateSnapshotIndex
    }
    Invoke-EsJson -Method Put -Path $path -Body $body
}

if ($ListSnapshots) {
    Invoke-EsJson -Method Get -Path "/_snapshot/$SnapshotRepo/_all"
}

if ($ForceMerge) {
    if ([string]::IsNullOrWhiteSpace($ForceMergeIndex)) {
        throw "ForceMergeIndex is required when -ForceMerge is set."
    }
    Invoke-EsJson -Method Post -Path "/$ForceMergeIndex/_forcemerge?max_num_segments=1"
}

if (-not ($RegisterRepo -or $CreateSnapshot -or $ListSnapshots -or $ForceMerge)) {
    Write-Host "Usage examples:"
    Write-Host "  .\scripts\es_single_node_ops.ps1 -RegisterRepo"
    Write-Host "  .\scripts\es_single_node_ops.ps1 -CreateSnapshot -CreateSnapshotIndex 'kb_*' -WaitForCompletion"
    Write-Host "  .\scripts\es_single_node_ops.ps1 -ListSnapshots"
    Write-Host "  .\scripts\es_single_node_ops.ps1 -ForceMerge -ForceMergeIndex 'kb_role_x_readonly'"
}
