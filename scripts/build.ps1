param(
    [string[]]$Tasks = @(':core:test', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:lintDebug'),
    [string]$JdkPath = '',
    [string]$SdkPath = ''
)

$ErrorActionPreference = 'Stop'
$workspacePath = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..')).TrimEnd('\')

function Assert-WorkspacePath([string]$CandidatePath) {
    $absolutePath = [System.IO.Path]::GetFullPath($CandidatePath)
    if ($absolutePath -ne $workspacePath -and -not $absolutePath.StartsWith($workspacePath + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Path is outside the workspace: $absolutePath"
    }
    $ancestorPath = $absolutePath
    while ($ancestorPath) {
        if (Test-Path -LiteralPath $ancestorPath) {
            $ancestorItem = Get-Item -LiteralPath $ancestorPath -Force
            if ($ancestorItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
                throw "Linked paths are not supported: $ancestorPath"
            }
        }
        $ancestorPath = [System.IO.Path]::GetDirectoryName($ancestorPath.TrimEnd('\'))
    }
}

Assert-WorkspacePath $workspacePath
if (-not $JdkPath) {
    $jdkCandidates = @($env:JAVA_HOME, (Join-Path $env:LOCALAPPDATA 'Programs\Rider\jbr'))
    foreach ($candidatePath in $jdkCandidates) {
        if (-not $candidatePath -or -not (Test-Path -LiteralPath (Join-Path $candidatePath 'release'))) { continue }
        $releaseText = Get-Content -LiteralPath (Join-Path $candidatePath 'release') -Raw
        if ($releaseText -match 'JAVA_VERSION="(17|18|19|20|21|22|23)\.') {
            $JdkPath = $candidatePath
            break
        }
    }
}
if (-not $JdkPath -or -not (Test-Path -LiteralPath (Join-Path $JdkPath 'bin\javac.exe'))) {
    throw 'Set -JdkPath to a JDK 17 through 23 installation. JDK 21 is recommended; JDK 25 cannot run Gradle 8.13.'
}
$jdkRelease = Get-Content -LiteralPath (Join-Path $JdkPath 'release') -Raw
if ($jdkRelease -notmatch 'JAVA_VERSION="(17|18|19|20|21|22|23)\.') {
    throw 'The selected JDK cannot run Gradle 8.13. Use JDK 21.'
}
if (-not $SdkPath) {
    $SdkPath = $env:ANDROID_HOME
    if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
}
foreach ($requiredPath in @('platforms\android-36\android.jar', 'build-tools\36.0.0\aapt2.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $SdkPath $requiredPath))) {
        throw "Required Android SDK component is missing: $requiredPath"
    }
}

$localPaths = @{}
foreach ($name in @('tmp', 'home', 'android', 'gradle')) {
    $targetPath = Join-Path $workspacePath ('.local\' + $name)
    Assert-WorkspacePath $targetPath
    New-Item -ItemType Directory -Path $targetPath -Force | Out-Null
    $localPaths[$name] = $targetPath
}
$environmentValues = @{
    JAVA_HOME = $JdkPath
    ANDROID_HOME = $SdkPath
    ANDROID_USER_HOME = $localPaths.android
    ANDROID_EMULATOR_HOME = $localPaths.android
    GRADLE_USER_HOME = $localPaths.gradle
    TEMP = $localPaths.tmp
    TMP = $localPaths.tmp
    JAVA_TOOL_OPTIONS = ('-XX:-UsePerfData -Duser.home="' + $localPaths.home + '" -Djava.io.tmpdir="' + $localPaths.tmp + '"')
}
$previousValues = @{}
foreach ($key in $environmentValues.Keys) {
    $previousValues[$key] = [System.Environment]::GetEnvironmentVariable($key, 'Process')
}
Push-Location $workspacePath
try {
    foreach ($key in $environmentValues.Keys) {
        [System.Environment]::SetEnvironmentVariable($key, $environmentValues[$key], 'Process')
    }
    & (Join-Path $workspacePath 'gradlew.bat') --no-daemon @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
    foreach ($key in $previousValues.Keys) {
        [System.Environment]::SetEnvironmentVariable($key, $previousValues[$key], 'Process')
    }
}
