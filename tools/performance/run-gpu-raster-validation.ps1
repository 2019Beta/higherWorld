param(
    [int] $Warmups = 10,
    [int] $Iterations = 30
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$sourcePath = Join-Path $repositoryRoot "tools\performance\GpuRasterValidation.java"
$kernelPath = Join-Path $repositoryRoot "src\main\resources\assets\higherworld\opencl\custom_terrain.cl"
$outputPath = Join-Path $repositoryRoot "build\gpu-smoke"
$gradleUserHome = if ($env:GRADLE_USER_HOME) {
    $env:GRADLE_USER_HOME
} elseif ($env:USERPROFILE) {
    Join-Path $env:USERPROFILE ".gradle"
} else {
    Join-Path ([Environment]::GetFolderPath("UserProfile")) ".gradle"
}
$lwjglVersion = "3.3.3"
$lwjglRoot = Join-Path $gradleUserHome "caches\modules-2\files-2.1\org.lwjgl\lwjgl\$lwjglVersion"
$openclRoot = Join-Path $gradleUserHome "caches\modules-2\files-2.1\org.lwjgl\lwjgl-opencl\$lwjglVersion"
$coreJar = Get-ChildItem -LiteralPath $lwjglRoot -Recurse -File -Filter "lwjgl-$lwjglVersion.jar" |
    Select-Object -First 1 -ExpandProperty FullName
$openclJar = Get-ChildItem -LiteralPath $openclRoot -Recurse -File -Filter "lwjgl-opencl-$lwjglVersion.jar" |
    Select-Object -First 1 -ExpandProperty FullName
$nativeJar = Get-ChildItem -LiteralPath $lwjglRoot -Recurse -File -Filter "lwjgl-$lwjglVersion-natives-windows.jar" |
    Select-Object -First 1 -ExpandProperty FullName

if (-not $coreJar -or -not $openclJar -or -not $nativeJar) {
    throw "LWJGL $lwjglVersion jars were not found under $gradleUserHome. Run Gradle once to populate its cache."
}

New-Item -ItemType Directory -Force $outputPath | Out-Null
$compileClasspath = "$coreJar;$openclJar"
& javac -cp $compileClasspath -d $outputPath $sourcePath
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

$runtimeClasspath = "$outputPath;$coreJar;$openclJar;$nativeJar"
& java -cp $runtimeClasspath GpuRasterValidation $kernelPath $Warmups $Iterations
if ($LASTEXITCODE -ne 0) {
    throw "GPU validation failed with exit code $LASTEXITCODE"
}
