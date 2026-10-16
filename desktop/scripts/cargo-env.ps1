# Runs a command with a complete MSVC toolset and Windows SDK in the
# environment, for machines with several Visual Studio installations where
# the newest toolset is incomplete. Tauri's native dependencies compile C/C++
# (resource embedding, WebView2 bindings), so unlike a pure-Rust build the
# compiler, its headers and the SDK's rc.exe must all agree.
#
#   pwsh scripts/cargo-env.ps1 npm run tauri dev
#   pwsh scripts/cargo-env.ps1 cargo build --manifest-path src-tauri/Cargo.toml
#   . scripts/cargo-env.ps1; cargo test --manifest-path src-tauri/Cargo.toml -- --ignored
#
# Run with a command, it sets the environment for that command only; run
# without one (dot-sourced), it sets the environment of the current session.
# On other platforms it changes nothing. The arguments are taken raw so that a
# `--` inside the command reaches it.
$ErrorActionPreference = 'Stop'
$Command = @($args)

if ($env:OS -eq 'Windows_NT') {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (-not (Test-Path -LiteralPath $vswhere)) { throw 'Visual Studio C++ build tools are required.' }
    $toolset = $null
    foreach ($installation in (& $vswhere -products '*' -prerelease -property installationPath)) {
        $root = Join-Path $installation 'VC\Tools\MSVC'
        if (-not (Test-Path -LiteralPath $root)) { continue }
        foreach ($candidate in (Get-ChildItem -LiteralPath $root -Directory | Sort-Object Name -Descending)) {
            $bin = Join-Path $candidate.FullName 'bin\Hostx64\x64'
            if ((Test-Path -LiteralPath (Join-Path $bin 'cl.exe')) -and
                (Test-Path -LiteralPath (Join-Path $bin 'link.exe')) -and
                (Test-Path -LiteralPath (Join-Path $candidate.FullName 'include\excpt.h')) -and
                (Test-Path -LiteralPath (Join-Path $candidate.FullName 'lib\x64\msvcrt.lib'))) {
                $toolset = $candidate.FullName
                break
            }
        }
        if ($toolset) { break }
    }
    if (-not $toolset) { throw 'No complete x64 MSVC toolset (cl, link, headers, libraries) found.' }

    $sdkRoot = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10'
    $sdk = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'Include') -Directory | Sort-Object Name -Descending | Where-Object {
        (Test-Path -LiteralPath (Join-Path $_.FullName 'um\windows.h')) -and
        (Test-Path -LiteralPath (Join-Path $sdkRoot "Lib\$($_.Name)\um\x64\kernel32.lib")) -and
        (Test-Path -LiteralPath (Join-Path $sdkRoot "bin\$($_.Name)\x64\rc.exe"))
    } | Select-Object -First 1
    if (-not $sdk) { throw 'A Windows SDK with headers, x64 libraries and rc.exe is required.' }
    $v = $sdk.Name
    $bin = Join-Path $toolset 'bin\Hostx64\x64'

    $env:CC = Join-Path $bin 'cl.exe'
    $env:CXX = $env:CC
    $env:CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER = Join-Path $bin 'link.exe'
    $env:INCLUDE = @(
        (Join-Path $toolset 'include'),
        (Join-Path $sdkRoot "Include\$v\ucrt"),
        (Join-Path $sdkRoot "Include\$v\um"),
        (Join-Path $sdkRoot "Include\$v\shared"),
        (Join-Path $sdkRoot "Include\$v\winrt"),
        (Join-Path $sdkRoot "Include\$v\cppwinrt")
    ) -join ';'
    $env:LIB = @(
        (Join-Path $toolset 'lib\x64'),
        (Join-Path $sdkRoot "Lib\$v\um\x64"),
        (Join-Path $sdkRoot "Lib\$v\ucrt\x64")
    ) -join ';'
    $env:PATH = @($bin, (Join-Path $sdkRoot "bin\$v\x64"), $env:PATH) -join ';'
}

if ($Command.Count -gt 0) {
    & $Command[0] @($Command | Select-Object -Skip 1)
    exit $LASTEXITCODE
}
