#Requires -Version 7.0
<#
.SYNOPSIS
    Exercises every endpoint of the running urlshorty API against real HTTP.

.DESCRIPTION
    Start the application first (.\mvnw.cmd spring-boot:run), then run this script.
    It walks through the whole life cycle of a short URL - create, retrieve, count,
    update, delete - and prints one PASS or FAIL line per check. The script exits
    with code 1 if any check fails, so it can be wired into a pipeline.

.EXAMPLE
    .\scripts\smoke-test.ps1
    .\scripts\smoke-test.ps1 -BaseUrl http://localhost:9090
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
$script:failures = 0

function Invoke-Api {
    param(
        [Parameter(Mandatory)][string]$Method,
        [Parameter(Mandatory)][string]$Path,
        [string]$Body
    )

    $request = @{
        Method              = $Method
        Uri                 = "$BaseUrl$Path"
        SkipHttpErrorCheck  = $true
        TimeoutSec          = 15
    }
    if ($Body) {
        $request.ContentType = "application/json"
        $request.Body = $Body
    }
    return Invoke-WebRequest @request
}

function Check {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)]$Actual,
        [Parameter(Mandatory)]$Expected
    )

    if ("$Actual" -eq "$Expected") {
        Write-Host ("  [PASS] {0,-46} {1}" -f $Name, $Actual) -ForegroundColor Green
    }
    else {
        Write-Host ("  [FAIL] {0,-46} expected '{1}' but got '{2}'" -f $Name, $Expected, $Actual) -ForegroundColor Red
        $script:failures++
    }
}

function IsoStamp {
    param($Value)

    if ($Value -is [datetime]) {
        return $Value.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
    }
    return "$Value"
}

Write-Host ""
Write-Host "urlshorty smoke test against $BaseUrl" -ForegroundColor Cyan
Write-Host ""

# ---------------------------------------------------------------- create
$createBody = '{"url":"https://www.example.com/some/long/url"}'
$created = Invoke-Api -Method POST -Path "/shorten" -Body $createBody
Check "POST /shorten returns 201" $created.StatusCode 201

$record = $created.Content | ConvertFrom-Json
Check "response contains the submitted URL" $record.url "https://www.example.com/some/long/url"
Check "response contains a 7 character code" ($record.shortCode.Length -eq 7) $true

$shortCode = $record.shortCode
Check "Location header points at the new code" ($created.Headers.Location | Select-Object -First 1) "/shorten/$shortCode"

# -------------------------------------------------------------- retrieve
$fetched = Invoke-Api -Method GET -Path "/shorten/$shortCode"
Check "GET /shorten/{code} returns 200" $fetched.StatusCode 200
Check "lookup returns the original URL" ($fetched.Content | ConvertFrom-Json).url $record.url

# --------------------------------------------------------- access counts
Invoke-Api -Method GET -Path "/shorten/$shortCode" | Out-Null
Invoke-Api -Method GET -Path "/shorten/$shortCode" | Out-Null
$stats = Invoke-Api -Method GET -Path "/shorten/$shortCode/stats"
Check "GET /shorten/{code}/stats returns 200" $stats.StatusCode 200
Check "three lookups were counted" ($stats.Content | ConvertFrom-Json).accessCount 3

# ---------------------------------------------------------------- update
Start-Sleep -Milliseconds 5
$updated = Invoke-Api -Method PUT -Path "/shorten/$shortCode" -Body '{"url":"https://www.example.com/some/updated/url"}'
Check "PUT /shorten/{code} returns 200" $updated.StatusCode 200

$updatedRecord = $updated.Content | ConvertFrom-Json
Check "update changes the URL" $updatedRecord.url "https://www.example.com/some/updated/url"
Check "createdAt is preserved" (IsoStamp $updatedRecord.createdAt) (IsoStamp $record.createdAt)
Check "updatedAt moves forward" ((IsoStamp $updatedRecord.updatedAt) -ne (IsoStamp $record.updatedAt)) $true

# ------------------------------------------------------- validation and 404
$invalid = Invoke-Api -Method POST -Path "/shorten" -Body '{"url":"not a url"}'
Check "invalid URL returns 400" $invalid.StatusCode 400
Check "error body explains the field" (($invalid.Content | ConvertFrom-Json).fieldErrors.url.Length -gt 0) $true

$missing = Invoke-Api -Method GET -Path "/shorten/nosuchcode"
Check "unknown code returns 404" $missing.StatusCode 404

$notFoundDelete = Invoke-Api -Method DELETE -Path "/shorten/nosuchcode"
Check "deleting an unknown code returns 404" $notFoundDelete.StatusCode 404

# ---------------------------------------------------------------- delete
$deleted = Invoke-Api -Method DELETE -Path "/shorten/$shortCode"
Check "DELETE /shorten/{code} returns 204" $deleted.StatusCode 204

$afterDelete = Invoke-Api -Method GET -Path "/shorten/$shortCode"
Check "deleted code is gone" $afterDelete.StatusCode 404

# ---------------------------------------------------------------- result
Write-Host ""
if ($script:failures -eq 0) {
    Write-Host "All checks passed." -ForegroundColor Green
    exit 0
}

Write-Host "$script:failures check(s) failed." -ForegroundColor Red
exit 1
