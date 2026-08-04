param(
    [Parameter(Mandatory = $true)]
    [string]$Destination
)

$ErrorActionPreference = "Stop"
$version = "1.0.3485.44"
$package = Join-Path $env:TEMP "microsoft.web.webview2.$version.zip"
$expanded = Join-Path $env:TEMP "createcinema-webview2-$version"
$url = "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$version/microsoft.web.webview2.$version.nupkg"

Invoke-WebRequest -Uri $url -OutFile $package
if (Test-Path $expanded) {
    Remove-Item $expanded -Recurse -Force
}
Expand-Archive -Path $package -DestinationPath $expanded

New-Item (Join-Path $Destination "include") -ItemType Directory -Force | Out-Null
New-Item (Join-Path $Destination "x64") -ItemType Directory -Force | Out-Null
Copy-Item (Join-Path $expanded "build/native/include/WebView2.h") (Join-Path $Destination "include/WebView2.h") -Force
Copy-Item (Join-Path $expanded "build/native/include/WebView2EnvironmentOptions.h") (Join-Path $Destination "include/WebView2EnvironmentOptions.h") -Force
Copy-Item (Join-Path $expanded "build/native/x64/WebView2LoaderStatic.lib") (Join-Path $Destination "x64/WebView2LoaderStatic.lib") -Force
